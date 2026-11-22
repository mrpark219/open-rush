package me.park.openrush.reservation.application

import me.park.openrush.reservation.adapter.output.persistence.ReservationPersistenceAdapter
import me.park.openrush.support.database.DatabaseIntegrationTest
import me.park.openrush.support.database.prepareConcurrentConnections
import me.park.openrush.support.fixture.ConcertFixture
import me.park.openrush.support.fixture.InventoryFixture
import me.park.openrush.reservation.application.ReleaseReservationHoldResources
import me.park.openrush.reservation.application.ReservationService
import me.park.openrush.reservation.application.provided.*
import me.park.openrush.reservation.application.required.ReservationRepositoryPort
import me.park.openrush.reservation.domain.exception.ReservationRuleViolationException
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.springframework.context.annotation.Primary
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.TransactionDefinition
import org.springframework.transaction.support.TransactionTemplate
import java.sql.Timestamp
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@Import(ExpireReservationHoldTest.Configuration::class)
@DatabaseIntegrationTest(
    properties = ["reservation.expiration.batch-size=2",
        "spring.datasource.hikari.maximum-pool-size=16", "reservation.expiration.lock-timeout-ms=200"]
)
class ExpireReservationHoldTest {
    @Autowired
    private lateinit var createReservationUseCase: CreateReservationUseCase
    @Autowired
    private lateinit var cancelReservationHoldUseCase: CancelReservationHoldUseCase
    @Autowired
    private lateinit var expireReservationHoldUseCase: ExpireReservationHoldUseCase
    @Autowired
    private lateinit var expireReservationHoldsUseCase: ExpireReservationHoldsUseCase
    @Autowired
    private lateinit var reservationRepository: ReservationRepositoryPort
    @Autowired
    private lateinit var coordinatedExpiration: CoordinatedExpiration
    @Autowired
    private lateinit var jdbcTemplate: JdbcTemplate
    @Autowired
    private lateinit var reservationPersistenceAdapter: ReservationPersistenceAdapter
    @Autowired
    private lateinit var releaseReservationHoldResources: ReleaseReservationHoldResources
    @Autowired
    private lateinit var transactionManager: PlatformTransactionManager
    private var performanceId = 0L
    private lateinit var seatIds: List<Long>
    private val now = Instant.parse("2026-11-04T00:00:00Z")

    @BeforeEach
    fun setUp() {
        coordinatedExpiration.beforeUpdate.set(null)

        val concertId = ConcertFixture(jdbcTemplate).createConcert()
        performanceId = ConcertFixture(jdbcTemplate).createPerformance(
            concertId = concertId,
            startsAt = Instant.parse("2026-11-06T00:00:00Z"),
            bookingOpensAt = Instant.parse("2026-11-03T00:00:00Z"),
            bookingClosesAt = Instant.parse("2026-11-05T00:00:00Z"),
        )
        seatIds = (1..8).map {
            InventoryFixture(jdbcTemplate).createSeat(performanceId, "A$it", amount = 50_000)
        }
    }

    @Test
    fun `만료 시각부터 예매 전체를 만료하고 해당 좌석과 한도만 반환한다`() {
        val due = reserve(seatIds.take(2))
        val active = reserve(listOf(seatIds[2]))
        makeDue(due)
        jdbcTemplate.update("UPDATE reservations SET modified_at = '2000-01-01'")
        jdbcTemplate.update("UPDATE performance_seats SET modified_at = '2000-01-01'")
        jdbcTemplate.update("UPDATE concert_purchase_quotas SET modified_at = '2000-01-01'")

        assertTrue(expireReservationHoldUseCase.expireHold(due))

        assertEquals("EXPIRED", status(due))
        assertEquals("HELD", status(active))
        assertEquals(1, allocated())
        assertEquals(
            2,
            count(
                "SELECT count(*) FROM performance_seats WHERE id IN (?, ?) AND status = 'AVAILABLE' AND reservation_id IS NULL",
                seatIds[0],
                seatIds[1]
            )
        )
        assertEquals(2, count("SELECT count(*) FROM reservation_items WHERE reservation_id = ?", due))
        assertEquals(1, count("SELECT count(*) FROM reservations WHERE id = ? AND modified_at > '2000-01-01'", due))
        assertEquals(
            2,
            count(
                "SELECT count(*) FROM performance_seats WHERE id IN (?, ?) AND modified_at > '2000-01-01'",
                seatIds[0],
                seatIds[1]
            )
        )
        assertEquals(1, count("SELECT count(*) FROM concert_purchase_quotas WHERE modified_at > '2000-01-01'"))
    }

    @Test
    fun `만료 전이거나 존재하지 않는 예매는 건너뛴다`() {
        val active = reserve(listOf(seatIds[0]))
        jdbcTemplate.update("UPDATE reservations SET expires_at = ? WHERE id = ?", Timestamp.from(now.plusNanos(1000)), active)
        assertFalse(expireReservationHoldUseCase.expireHold(active))
        assertFalse(expireReservationHoldUseCase.expireHold(Long.MAX_VALUE))
        assertEquals("HELD", status(active))
        assertEquals(1, allocated())
        assertFailsWith<IllegalArgumentException> { expireReservationHoldUseCase.expireHold(0) }
    }

    @Test
    fun `이미 취소 확정 만료된 예매는 다시 반환하지 않는다`() {
        val cancelled = reserve(listOf(seatIds[0]))
        cancelReservationHoldUseCase.cancelHold(CancelReservationHoldCommand(1, cancelled))
        val confirmed = reserve(listOf(seatIds[1]))
        makeDue(confirmed)
        jdbcTemplate.update("UPDATE reservations SET status = 'CONFIRMED', confirmed_at = held_at WHERE id = ?", confirmed)
        jdbcTemplate.update("UPDATE performance_seats SET status = 'RESERVED' WHERE reservation_id = ?", confirmed)
        val expired = reserve(listOf(seatIds[2]))
        listOf(cancelled, expired).forEach { makeDue(it) }
        assertTrue(expireReservationHoldUseCase.expireHold(expired))

        listOf(cancelled, confirmed, expired).forEach { assertFalse(expireReservationHoldUseCase.expireHold(it)) }
        assertEquals(1, allocated())
        assertEquals(
            confirmed,
            jdbcTemplate.queryForObject("SELECT reservation_id FROM performance_seats WHERE id = ?", Long::class.java, seatIds[1])
        )
        assertEquals(
            "RESERVED",
            jdbcTemplate.queryForObject("SELECT status FROM performance_seats WHERE id = ?", String::class.java, seatIds[1])
        )
    }

    @Test
    fun `여러 작업자가 같은 만료 예매를 처리하면 한 번만 반환한다`() {
        val due = reserve(seatIds.take(2))
        reserve(listOf(seatIds[2]))
        makeDue(due)
        val results = race(List(8) { due })
        assertEquals(1, results.count { it })
        assertEquals("EXPIRED", status(due))
        assertEquals(1, allocated())
        assertEquals(1, count("SELECT count(*) FROM performance_seats WHERE status = 'HELD'"))
    }

    @Test
    fun `같은 사용자의 여러 예매를 동시에 만료해도 한도를 정확히 반환한다`() {
        val ids = seatIds.take(4).map { reserve(listOf(it)).also(::makeDue) }
        assertTrue(race(ids).all { it })
        assertEquals(0, allocated())
        assertEquals(0, count("SELECT count(*) FROM performance_seats WHERE status = 'HELD'"))
    }

    @Test
    fun `만료 후 재선점한 좌석은 이전 예매의 재처리로 해제되지 않는다`() {
        val old = reserve(listOf(seatIds[0]))
        makeDue(old)
        assertTrue(expireReservationHoldUseCase.expireHold(old))
        val replacement = reserve(listOf(seatIds[0]))
        assertFalse(expireReservationHoldUseCase.expireHold(old))
        assertEquals("HELD", status(replacement))
        assertEquals(
            replacement,
            jdbcTemplate.queryForObject("SELECT reservation_id FROM performance_seats WHERE id = ?", Long::class.java, seatIds[0])
        )
        assertEquals(1, allocated())
    }

    @Test
    fun `좌석 반환 실패 시 부분 해제와 한도와 만료 상태를 모두 롤백한다`() {
        val due = reserve(seatIds.take(2))
        val other = reserve(listOf(seatIds[2]))
        makeDue(due)
        jdbcTemplate.update("UPDATE performance_seats SET reservation_id = ? WHERE id = ?", other, seatIds[1])
        assertFailsWith<IllegalStateException> { expireReservationHoldUseCase.expireHold(due) }
        assertEquals("HELD", status(due))
        assertEquals(3, allocated())
        assertEquals(3, count("SELECT count(*) FROM performance_seats WHERE status = 'HELD'"))
        assertEquals(
            other,
            jdbcTemplate.queryForObject("SELECT reservation_id FROM performance_seats WHERE id = ?", Long::class.java, seatIds[1])
        )
    }

    @Test
    fun `한도가 부족하거나 행이 없으면 만료 전환을 롤백한다`() {
        val due = reserve(seatIds.take(2))
        makeDue(due)
        jdbcTemplate.update("UPDATE concert_purchase_quotas SET allocated_quantity = 1")
        assertFailsWith<IllegalStateException> { expireReservationHoldUseCase.expireHold(due) }
        assertEquals("HELD", status(due))
        assertEquals(1, allocated())
        jdbcTemplate.update("DELETE FROM concert_purchase_quotas")
        assertFailsWith<IllegalStateException> { expireReservationHoldUseCase.expireHold(due) }
        assertEquals("HELD", status(due))
        assertEquals(2, count("SELECT count(*) FROM performance_seats WHERE status = 'HELD'"))
    }

    @Test
    fun `조회 크기를 넘어도 모두 처리하고 실패한 앞 예매는 다음 실행에서 재시도한다`() {
        val due = seatIds.take(5).mapIndexed { i, seat -> reserve(listOf(seat), i + 1L).also(::makeDue) }
        val active = reserve(listOf(seatIds[5]), 6)
        jdbcTemplate.update("UPDATE concert_purchase_quotas SET allocated_quantity = 0 WHERE user_id = 1")

        assertEquals(ExpireReservationHoldsResult(4, 0, 1), expireReservationHoldsUseCase.expireHolds())
        assertEquals("HELD", status(due.first()))
        due.drop(1).forEach { assertEquals("EXPIRED", status(it)) }
        assertEquals("HELD", status(active))
        assertEquals(1, allocated())

        jdbcTemplate.update("UPDATE concert_purchase_quotas SET allocated_quantity = 1 WHERE user_id = 1")
        assertEquals(ExpireReservationHoldsResult(1, 0, 0), expireReservationHoldsUseCase.expireHolds())
        assertEquals(ExpireReservationHoldsResult(0, 0, 0), expireReservationHoldsUseCase.expireHolds())
        assertEquals(1, allocated())
    }

    @Test
    fun `대상 조회 후 다른 요청이 확정하면 만료 전환을 건너뛴다`() {
        val due = reserve(listOf(seatIds[0]))
        makeDue(due)
        assertEquals(listOf(due), reservationRepository.findExpiredReservationIds(now, 0, 2))
        coordinatedExpiration.beforeUpdate.set {
            TransactionTemplate(transactionManager).apply {
                propagationBehavior = TransactionDefinition.PROPAGATION_REQUIRES_NEW
            }.executeWithoutResult {
                jdbcTemplate.update("UPDATE reservations SET status = 'CONFIRMED', confirmed_at = held_at WHERE id = ?", due)
                jdbcTemplate.update("UPDATE performance_seats SET status = 'RESERVED' WHERE reservation_id = ?", due)
            }
        }
        assertEquals(ExpireReservationHoldsResult(0, 1, 0), expireReservationHoldsUseCase.expireHolds())
        coordinatedExpiration.beforeUpdate.set(null)
        assertEquals("CONFIRMED", status(due))
        assertEquals(1, allocated())
    }

    @Test
    fun `만료 1나노초 전에는 선점을 취소할 수 있다`() {
        val due = reserve(listOf(seatIds[0]))
        makeDue(due)
        val cancelReservationHoldUseCase = cancellationService(Clock.fixed(now.minusNanos(1), ZoneOffset.UTC), reservationPersistenceAdapter)

        TransactionTemplate(transactionManager).executeWithoutResult {
            cancelReservationHoldUseCase.cancelHold(CancelReservationHoldCommand(1, due))
        }

        assertEquals("CANCELLED", status(due))
        assertEquals(0, allocated())
    }

    @Test
    fun `만료 직전 접수한 취소와 만료가 경합해도 한 번만 반환한다`() {
        val due = reserve(seatIds.take(2))
        reserve(listOf(seatIds[2]))
        makeDue(due)
        checkNotNull(jdbcTemplate.dataSource).prepareConcurrentConnections(2)
        val barrier = CyclicBarrier(2)
        coordinatedExpiration.beforeUpdate.set { barrier.await(10, TimeUnit.SECONDS) }
        // 취소는 만료 직전 시각에 접수되고, 실제 UPDATE는 만료 작업과 동시에 진행한다.
        val cancelReservationHoldUseCase = cancellationService(
            Clock.fixed(now.minusSeconds(1), ZoneOffset.UTC),
            object : ReservationRepositoryPort by reservationPersistenceAdapter {
                override fun cancelHeld(reservationId: Long, userId: Long, now: Instant) {
                    barrier.await(10, TimeUnit.SECONDS)
                    reservationPersistenceAdapter.cancelHeld(reservationId, userId, now)
                }
            },
        )
        val executor = Executors.newFixedThreadPool(2)
        try {
            val expiry = executor.submit<Boolean> { expireReservationHoldUseCase.expireHold(due) }
            val cancelled = executor.submit<Result<Unit>> {
                runCatching {
                    TransactionTemplate(transactionManager).executeWithoutResult {
                        cancelReservationHoldUseCase.cancelHold(CancelReservationHoldCommand(1, due))
                    }
                }
            }
            val expired = expiry.get(30, TimeUnit.SECONDS)
            val cancellationResult = cancelled.get(30, TimeUnit.SECONDS)
            assertEquals(1, listOf(expired, cancellationResult.isSuccess).count { it })
            if (expired) assertTrue(cancellationResult.exceptionOrNull() is ReservationRuleViolationException)
            assertEquals(if (expired) "EXPIRED" else "CANCELLED", status(due))
            assertEquals(1, allocated())
            assertEquals(1, count("SELECT count(*) FROM performance_seats WHERE status = 'HELD'"))
        } finally {
            coordinatedExpiration.beforeUpdate.set(null)
            executor.shutdownNow()
            assertTrue(executor.awaitTermination(10, TimeUnit.SECONDS))
        }
    }

    @ParameterizedTest
    @ValueSource(strings = ["reservation", "quota", "seat"])
    fun `예매 한도 좌석 잠금이 오래 유지돼도 해당 예매만 롤백하고 다음 예매를 만료한다`(resource: String) {
        val first = reserve(seatIds.take(2), 1).also(::makeDue)
        val second = reserve(listOf(seatIds[2]), 2).also(::makeDue)
        checkNotNull(jdbcTemplate.dataSource).prepareConcurrentConnections(2)
        val locked = java.util.concurrent.CountDownLatch(1)
        val release = java.util.concurrent.CountDownLatch(1)
        val executor = Executors.newFixedThreadPool(2)
        try {
            val holder = executor.submit {
                TransactionTemplate(transactionManager).executeWithoutResult {
                    when (resource) {
                        "reservation" -> jdbcTemplate.queryForList("SELECT id FROM reservations WHERE id = ? FOR UPDATE", first)
                        "quota" -> jdbcTemplate.queryForList("SELECT id FROM concert_purchase_quotas WHERE user_id = 1 FOR UPDATE")
                        "seat" -> jdbcTemplate.queryForList(
                            "SELECT id FROM performance_seats WHERE id = ? FOR UPDATE",
                            seatIds[1]
                        )

                        else -> error("알 수 없는 잠금 대상")
                    }
                    locked.countDown()
                    check(release.await(15, TimeUnit.SECONDS))
                }
            }
            assertTrue(locked.await(5, TimeUnit.SECONDS))
            val worker = executor.submit<ExpireReservationHoldsResult> { expireReservationHoldsUseCase.expireHolds() }
            // 잠금을 풀기 전에도 첫 예매를 건너뛰고 두 번째 예매 처리를 완료해야 한다.
            assertEquals(ExpireReservationHoldsResult(1, 0, 1), worker.get(5, TimeUnit.SECONDS))
            assertEquals("HELD", status(first))
            assertEquals("EXPIRED", status(second))
            assertEquals(2, allocated())
            assertEquals(
                2,
                count("SELECT count(*) FROM performance_seats WHERE reservation_id = ? AND status = 'HELD'", first)
            )
            assertEquals(
                1,
                count(
                    "SELECT count(*) FROM performance_seats WHERE id = ? AND status = 'AVAILABLE' AND reservation_id IS NULL",
                    seatIds[2]
                )
            )

            release.countDown()
            holder.get(5, TimeUnit.SECONDS)
            assertEquals(ExpireReservationHoldsResult(1, 0, 0), expireReservationHoldsUseCase.expireHolds())
            assertEquals("EXPIRED", status(first))
            assertEquals(0, allocated())
            assertEquals(0, count("SELECT count(*) FROM performance_seats WHERE status = 'HELD'"))
        } finally {
            release.countDown()
            executor.shutdownNow()
            assertTrue(executor.awaitTermination(10, TimeUnit.SECONDS))
        }
    }

    private fun race(ids: List<Long>): List<Boolean> {
        checkNotNull(jdbcTemplate.dataSource).prepareConcurrentConnections(ids.size)
        val barrier = CyclicBarrier(ids.size)
        coordinatedExpiration.beforeUpdate.set { barrier.await(10, TimeUnit.SECONDS) }
        val executor = Executors.newFixedThreadPool(ids.size)
        return try {
            ids.map { id -> executor.submit<Boolean> { expireReservationHoldUseCase.expireHold(id) } }
                .map { it.get(30, TimeUnit.SECONDS) }
        } finally {
            coordinatedExpiration.beforeUpdate.set(null)
            executor.shutdownNow()
            assertTrue(executor.awaitTermination(10, TimeUnit.SECONDS))
        }
    }

    private fun reserve(selected: List<Long>, user: Long = 1) =
        createReservationUseCase.create(CreateReservationCommand(user, performanceId, selected)).reservationId

    private fun makeDue(id: Long) {
        jdbcTemplate.update(
            "UPDATE reservations SET held_at = ?, expires_at = ? WHERE id = ?",
            Timestamp.from(now.minusSeconds(300)),
            Timestamp.from(now),
            id
        )
    }

    private fun status(id: Long) =
        jdbcTemplate.queryForObject("SELECT status FROM reservations WHERE id = ?", String::class.java, id)

    private fun allocated() = count("SELECT coalesce(sum(allocated_quantity), 0) FROM concert_purchase_quotas")
    private fun count(sql: String, vararg args: Any) = jdbcTemplate.queryForObject(sql, Int::class.java, *args)!!

    private inline fun <reified T : Any> unused(): T = org.mockito.Mockito.mock(T::class.java)

    private fun cancellationService(clock: Clock, reservationRepository: ReservationRepositoryPort) = ReservationService(
        getPerformanceUseCase = unused(), getSeatsUseCase = unused(), allocatePurchaseQuotaUseCase = unused(),
        holdSeatsUseCase = unused(),
        clock = clock,
        confirmSeatsUseCase = unused(), releaseReservationHoldResources = releaseReservationHoldResources,
        configureExpirationTransactionPort = unused(),
        reservationRepository = reservationRepository, reservationPaymentTransactionsUseCase = unused(), holdDurationMillis = 300000,
    )

    @TestConfiguration(proxyBeanMethods = false)
    class Configuration {
        @Bean
        @Primary
        fun expirationClock(): Clock = Clock.fixed(Instant.parse("2026-11-04T00:00:00Z"), ZoneOffset.UTC)

        @Bean
        @Primary
        fun coordinatedExpiration(reservationPersistenceAdapter: ReservationPersistenceAdapter) = CoordinatedExpiration(reservationPersistenceAdapter)
    }

    class CoordinatedExpiration(private val delegate: ReservationPersistenceAdapter) :
        ReservationRepositoryPort by delegate {
        val beforeUpdate = AtomicReference<(() -> Unit)?>(null)
        override fun expireHeld(reservationId: Long, now: Instant): Boolean {
            beforeUpdate.get()?.invoke()
            return delegate.expireHeld(reservationId, now)
        }
    }
}
