package me.park.openrush.reservation.application

import me.park.openrush.reservation.adapter.output.persistence.ReservationPersistenceAdapter
import me.park.openrush.support.database.DatabaseIntegrationTest
import me.park.openrush.support.database.prepareConcurrentConnections
import me.park.openrush.support.fixture.ConcertFixture
import me.park.openrush.support.fixture.InventoryFixture
import me.park.openrush.support.database.awaitBlockedTransaction
import me.park.openrush.reservation.application.provided.*
import me.park.openrush.reservation.application.required.ReservationRepositoryPort
import me.park.openrush.reservation.domain.exception.ReservationRuleViolationException
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.EnumSource
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.springframework.context.annotation.Primary
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.concurrent.CountDownLatch
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.*

@Import(ConfirmReservationTest.Configuration::class)
@DatabaseIntegrationTest
class ConfirmReservationTest {
    @Autowired
    private lateinit var createReservationUseCase: CreateReservationUseCase
    @Autowired
    private lateinit var confirmReservationUseCase: ConfirmReservationUseCase
    @Autowired
    private lateinit var cancelReservationHoldUseCase: CancelReservationHoldUseCase
    @Autowired
    private lateinit var expireReservationHoldUseCase: ExpireReservationHoldUseCase
    @Autowired
    private lateinit var coordinatedConfirmation: CoordinatedConfirmation
    @Autowired
    private lateinit var adjustableClock: AdjustableClock
    @Autowired
    private lateinit var jdbcTemplate: JdbcTemplate
    @Autowired
    private lateinit var transactionManager: PlatformTransactionManager
    private lateinit var seatIds: List<Long>
    private var performanceId = 0L

    @BeforeEach
    fun setUp() {
        adjustableClock.now = heldAt
        coordinatedConfirmation.beforeUpdate = null

        val concertId = ConcertFixture(jdbcTemplate).createConcert()
        performanceId = ConcertFixture(jdbcTemplate).createPerformance(
            concertId = concertId,
            startsAt = Instant.parse("2026-11-06T00:00:00Z"),
            bookingOpensAt = Instant.parse("2026-11-03T00:00:00Z"),
            bookingClosesAt = Instant.parse("2026-11-05T00:00:00Z"),
        )
        seatIds = (1..3).map { number ->
            InventoryFixture(jdbcTemplate).createSeat(performanceId, "A$number", amount = 50_000)
        }
    }

    @Test
    fun `기한 직전 확정하면 좌석과 한도를 유지하고 이후 취소와 만료를 거절한다`() {
        val reservation = createReservationUseCase.create(CreateReservationCommand(1, performanceId, seatIds.take(2)))
        adjustableClock.now = reservation.expiresAt.minusNanos(1)
        confirmReservationUseCase.confirm(ConfirmReservationCommand(1, reservation.reservationId))

        assertEquals("CONFIRMED", status(reservation.reservationId))
        assertEquals(reservation.expiresAt.minusNanos(1_000), confirmedAt(reservation.reservationId))
        assertEquals(2, seatsOwnedBy(reservation.reservationId, "RESERVED"))
        assertEquals(2, allocated())

        assertFailsWith<ReservationRuleViolationException> {
            cancelReservationHoldUseCase.cancelHold(
                CancelReservationHoldCommand(
                    1,
                    reservation.reservationId
                )
            )
        }
        adjustableClock.now = reservation.expiresAt
        assertFalse(expireReservationHoldUseCase.expireHold(reservation.reservationId))
        assertEquals(2, seatsOwnedBy(reservation.reservationId, "RESERVED"))
        assertEquals(2, allocated())
    }

    @Test
    fun `만료 시각에 도달한 선점은 확정할 수 없다`() {
        val reservation = createReservationUseCase.create(CreateReservationCommand(1, performanceId, seatIds.take(2)))
        adjustableClock.now = reservation.expiresAt

        assertFailsWith<ReservationRuleViolationException> {
            confirmReservationUseCase.confirm(
                ConfirmReservationCommand(
                    1,
                    reservation.reservationId
                )
            )
        }
        assertEquals("HELD", status(reservation.reservationId))
        assertNull(confirmedAt(reservation.reservationId))
        assertEquals(2, seatsOwnedBy(reservation.reservationId, "HELD"))
        assertEquals(2, allocated())
    }

    @Test
    fun `다른 사용자와 존재하지 않는 예매는 확정할 수 없다`() {
        val reservation = createReservationUseCase.create(CreateReservationCommand(1, performanceId, seatIds.take(2)))
        assertFailsWith<ReservationRuleViolationException> {
            confirmReservationUseCase.confirm(
                ConfirmReservationCommand(
                    2,
                    reservation.reservationId
                )
            )
        }
        assertFailsWith<ReservationRuleViolationException> { confirmReservationUseCase.confirm(ConfirmReservationCommand(1, Long.MAX_VALUE)) }
        assertEquals("HELD", status(reservation.reservationId))
        assertEquals(2, seatsOwnedBy(reservation.reservationId, "HELD"))
        assertEquals(2, allocated())
    }

    @Test
    fun `좌석 하나의 선점 소유자가 다르면 예매와 나머지 좌석의 확정도 롤백한다`() {
        val reservation = createReservationUseCase.create(CreateReservationCommand(1, performanceId, seatIds.take(2)))
        val other = createReservationUseCase.create(CreateReservationCommand(2, performanceId, listOf(seatIds[2])))
        jdbcTemplate.update("UPDATE performance_seats SET reservation_id = ? WHERE id = ?", other.reservationId, seatIds[1])

        assertFailsWith<IllegalStateException> {
            confirmReservationUseCase.confirm(
                ConfirmReservationCommand(
                    1,
                    reservation.reservationId
                )
            )
        }
        assertEquals("HELD", status(reservation.reservationId))
        assertNull(confirmedAt(reservation.reservationId))
        assertEquals(1, seatsOwnedBy(reservation.reservationId, "HELD"))
        assertEquals(2, seatsOwnedBy(other.reservationId, "HELD"))
        assertEquals(0, count("SELECT count(*) FROM performance_seats WHERE status = 'RESERVED'"))
        assertEquals(3, allocated())
    }

    @ParameterizedTest
    @EnumSource(CompetingTransition::class)
    fun `확정 검증 뒤 취소 또는 만료가 먼저 커밋되면 확정하지 않는다`(competing: CompetingTransition) {
        val reservation = createReservationUseCase.create(CreateReservationCommand(1, performanceId, seatIds.take(2)))
        checkNotNull(jdbcTemplate.dataSource).prepareConcurrentConnections(2)
        val validated = CountDownLatch(1)
        val proceed = CountDownLatch(1)
        coordinatedConfirmation.beforeUpdate = {
            validated.countDown()
            check(proceed.await(10, TimeUnit.SECONDS))
        }
        val executor = Executors.newSingleThreadExecutor()
        try {
            val pendingConfirmation = executor.submit<Throwable?> {
                runCatching {
                    confirmReservationUseCase.confirm(
                        ConfirmReservationCommand(
                            1,
                            reservation.reservationId
                        )
                    )
                }.exceptionOrNull()
            }
            assertTrue(validated.await(5, TimeUnit.SECONDS))
            // 확정 트랜잭션이 상태를 읽고 나면 다른 트랜잭션이 먼저 상태를 전환한다.
            when (competing) {
                CompetingTransition.CANCELLED -> cancelReservationHoldUseCase.cancelHold(
                    CancelReservationHoldCommand(
                        1,
                        reservation.reservationId
                    )
                )

                CompetingTransition.EXPIRED -> {
                    adjustableClock.now = reservation.expiresAt
                    assertTrue(expireReservationHoldUseCase.expireHold(reservation.reservationId))
                }
            }
            proceed.countDown()
            assertTrue(pendingConfirmation.get(5, TimeUnit.SECONDS) is ReservationRuleViolationException)
            assertEquals(competing.name, status(reservation.reservationId))
            assertNull(confirmedAt(reservation.reservationId))
            assertEquals(0, allocated())
            assertEquals(
                3,
                count("SELECT count(*) FROM performance_seats WHERE status = 'AVAILABLE' AND reservation_id IS NULL")
            )
        } finally {
            proceed.countDown()
            coordinatedConfirmation.beforeUpdate = null
            executor.shutdownNow()
            assertTrue(executor.awaitTermination(10, TimeUnit.SECONDS))
        }
    }

    @Test
    fun `기한 전에 검증한 확정은 행 락 대기 중 만료 시각이 지나도 상태가 HELD이면 성공한다`() {
        val reservation = createReservationUseCase.create(CreateReservationCommand(1, performanceId, seatIds.take(2)))
        val validationTime = reservation.expiresAt.minusNanos(1_000)
        adjustableClock.now = validationTime
        checkNotNull(jdbcTemplate.dataSource).prepareConcurrentConnections(2)
        val executor = Executors.newSingleThreadExecutor()
        try {
            val pending = checkNotNull(TransactionTemplate(transactionManager).execute {
                jdbcTemplate.queryForObject(
                    "SELECT id FROM reservations WHERE id = ? FOR NO KEY UPDATE",
                    Long::class.java,
                    reservation.reservationId
                )
                val blockerPid = checkNotNull(jdbcTemplate.queryForObject("SELECT pg_backend_pid()", Int::class.java))
                val confirmationRequest = executor.submit {
                    confirmReservationUseCase.confirm(ConfirmReservationCommand(1, reservation.reservationId))
                }
                jdbcTemplate.awaitBlockedTransaction(blockerPid)
                adjustableClock.now = reservation.expiresAt.plusSeconds(1)
                assertEquals("HELD", status(reservation.reservationId))
                confirmationRequest
            })

            pending.get(5, TimeUnit.SECONDS)
            assertEquals("CONFIRMED", status(reservation.reservationId))
            assertEquals(validationTime, confirmedAt(reservation.reservationId))
            assertEquals(2, seatsOwnedBy(reservation.reservationId, "RESERVED"))
            assertEquals(2, allocated())
        } finally {
            executor.shutdownNow()
            assertTrue(executor.awaitTermination(10, TimeUnit.SECONDS))
        }
    }

    @Test
    fun `동시에 같은 예매를 확정해도 한 번만 전환한다`() {
        val reservation = createReservationUseCase.create(CreateReservationCommand(1, performanceId, seatIds.take(2)))
        checkNotNull(jdbcTemplate.dataSource).prepareConcurrentConnections(2)
        val barrier = CyclicBarrier(2)
        coordinatedConfirmation.beforeUpdate = { barrier.await(5, TimeUnit.SECONDS) }
        val executor = Executors.newFixedThreadPool(2)
        try {
            val results = List(2) {
                executor.submit<Throwable?> {
                    runCatching {
                        confirmReservationUseCase.confirm(
                            ConfirmReservationCommand(
                                1,
                                reservation.reservationId
                            )
                        )
                    }.exceptionOrNull()
                }
            }.map { it.get(10, TimeUnit.SECONDS) }
            assertEquals(1, results.count { it == null })
            assertTrue(results.single { it != null } is ReservationRuleViolationException)
            assertEquals("CONFIRMED", status(reservation.reservationId))
            assertEquals(2, seatsOwnedBy(reservation.reservationId, "RESERVED"))
            assertEquals(2, allocated())
        } finally {
            coordinatedConfirmation.beforeUpdate = null
            executor.shutdownNow()
            assertTrue(executor.awaitTermination(10, TimeUnit.SECONDS))
        }
    }

    enum class CompetingTransition { CANCELLED, EXPIRED }

    private fun status(id: Long) =
        jdbcTemplate.queryForObject("SELECT status FROM reservations WHERE id = ?", String::class.java, id)

    private fun confirmedAt(id: Long) =
        jdbcTemplate.queryForObject("SELECT confirmed_at FROM reservations WHERE id = ?", java.sql.Timestamp::class.java, id)
            ?.toInstant()

    private fun seatsOwnedBy(id: Long, status: String) =
        count("SELECT count(*) FROM performance_seats WHERE reservation_id = ? AND status = ?", id, status)

    private fun allocated() = count("SELECT coalesce(sum(allocated_quantity), 0) FROM concert_purchase_quotas")
    private fun count(sql: String, vararg args: Any) = jdbcTemplate.queryForObject(sql, Int::class.java, *args)!!

    @TestConfiguration(proxyBeanMethods = false)
    class Configuration {
        @Bean
        @Primary
        fun confirmationClock() = AdjustableClock()

        @Bean
        @Primary
        fun coordinatedConfirmation(reservationPersistenceAdapter: ReservationPersistenceAdapter) = CoordinatedConfirmation(reservationPersistenceAdapter)
    }

    class AdjustableClock : Clock() {
        @Volatile
        var now: Instant = heldAt
        override fun instant(): Instant = now
        override fun getZone(): ZoneId = ZoneOffset.UTC
        override fun withZone(zone: ZoneId): Clock = Clock.fixed(now, zone)
    }

    class CoordinatedConfirmation(private val delegate: ReservationRepositoryPort) :
        ReservationRepositoryPort by delegate {
        @Volatile
        var beforeUpdate: (() -> Unit)? = null
        override fun confirmHeld(reservationId: Long, userId: Long, now: Instant) {
            beforeUpdate?.invoke()
            delegate.confirmHeld(reservationId, userId, now)
        }
    }

    companion object {
        private val heldAt = Instant.parse("2026-11-04T00:00:00Z")
    }
}
