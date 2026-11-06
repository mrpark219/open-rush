package me.park.openrush.reservation.adapter.output.persistence

import me.park.openrush.TestcontainersConfiguration
import me.park.openrush.reservation.application.CancelReservationHoldService
import me.park.openrush.reservation.application.port.output.CancelHeldReservationPort
import me.park.openrush.reservation.application.port.output.ReleasePerformanceSeatsPort
import me.park.openrush.reservation.application.port.output.ReleasePurchaseQuotaPort
import me.park.openrush.reservation.domain.exception.ReservationRuleViolation
import me.park.openrush.reservation.application.port.input.CancelReservationHoldCommand
import me.park.openrush.reservation.application.port.input.CancelReservationHoldUseCase
import me.park.openrush.reservation.application.port.input.CreateReservationCommand
import me.park.openrush.reservation.application.port.input.CreateReservationUseCase
import me.park.openrush.reservation.application.port.input.ExpireReservationHoldUseCase
import me.park.openrush.reservation.application.port.input.ExpireReservationHoldsResult
import me.park.openrush.reservation.application.port.input.ExpireReservationHoldsUseCase
import me.park.openrush.reservation.application.port.output.ExpireHeldReservationPort
import me.park.openrush.reservation.application.port.output.FindExpiredReservationHoldsPort
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
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
import kotlin.test.assertFalse
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

@Import(TestcontainersConfiguration::class, ExpireReservationHoldTest.Configuration::class)
@SpringBootTest(properties = ["reservation.expiration.enabled=false", "reservation.expiration.batch-size=2",
    "spring.datasource.hikari.maximum-pool-size=16", "reservation.expiration.lock-timeout-ms=200"])
class ExpireReservationHoldTest {
    @Autowired private lateinit var create: CreateReservationUseCase
    @Autowired private lateinit var cancel: CancelReservationHoldUseCase
    @Autowired private lateinit var expiration: ExpireReservationHoldUseCase
    @Autowired private lateinit var batch: ExpireReservationHoldsUseCase
    @Autowired private lateinit var candidates: FindExpiredReservationHoldsPort
    @Autowired private lateinit var transition: CoordinatedExpiration
    @Autowired private lateinit var jdbc: JdbcTemplate
    @Autowired private lateinit var adapter: ReservationPersistenceAdapter
    @Autowired private lateinit var quotaRelease: ReleasePurchaseQuotaPort
    @Autowired private lateinit var seatRelease: ReleasePerformanceSeatsPort
    @Autowired private lateinit var transactionManager: PlatformTransactionManager
    private var performanceId = 0L
    private lateinit var seats: List<Long>
    private val now = Instant.parse("2026-11-04T00:00:00Z")

    @BeforeEach
    fun setUp() {
        transition.beforeUpdate.set(null)
        jdbc.execute("TRUNCATE concerts CASCADE")
        val concertId = id("INSERT INTO concerts(title, description, max_tickets_per_user) VALUES ('공연', '', 4) RETURNING id")
        performanceId = id("""INSERT INTO performances(concert_id, venue, starts_at, booking_opens_at, booking_closes_at)
            VALUES (?, '공연장', '2026-11-06T00:00:00Z', '2026-11-03T00:00:00Z', '2026-11-05T00:00:00Z') RETURNING id""", concertId)
        seats = (1..8).map {
            id("""INSERT INTO performance_seats(performance_id, seat_number, price, status)
                VALUES (?, ?, 50000, 'AVAILABLE') RETURNING id""", performanceId, "A$it")
        }
    }

    @Test
    fun `만료 시각부터 예매 전체를 만료하고 해당 좌석과 한도만 반환한다`() {
        val due = reserve(seats.take(2))
        val active = reserve(listOf(seats[2]))
        makeDue(due)
        jdbc.update("UPDATE reservations SET modified_at = '2000-01-01'")
        jdbc.update("UPDATE performance_seats SET modified_at = '2000-01-01'")
        jdbc.update("UPDATE concert_purchase_quotas SET modified_at = '2000-01-01'")

        assertTrue(expiration.expireHold(due))

        assertEquals("EXPIRED", status(due))
        assertEquals("HELD", status(active))
        assertEquals(1, allocated())
        assertEquals(2, count("SELECT count(*) FROM performance_seats WHERE id IN (?, ?) AND status = 'AVAILABLE' AND reservation_id IS NULL", seats[0], seats[1]))
        assertEquals(2, count("SELECT count(*) FROM reservation_items WHERE reservation_id = ?", due))
        assertEquals(1, count("SELECT count(*) FROM reservations WHERE id = ? AND modified_at > '2000-01-01'", due))
        assertEquals(2, count("SELECT count(*) FROM performance_seats WHERE id IN (?, ?) AND modified_at > '2000-01-01'", seats[0], seats[1]))
        assertEquals(1, count("SELECT count(*) FROM concert_purchase_quotas WHERE modified_at > '2000-01-01'"))
    }

    @Test
    fun `만료 전이거나 존재하지 않는 예매는 건너뛴다`() {
        val active = reserve(listOf(seats[0]))
        jdbc.update("UPDATE reservations SET expires_at = ? WHERE id = ?", Timestamp.from(now.plusNanos(1000)), active)
        assertFalse(expiration.expireHold(active))
        assertFalse(expiration.expireHold(Long.MAX_VALUE))
        assertEquals("HELD", status(active))
        assertEquals(1, allocated())
        assertFailsWith<IllegalArgumentException> { expiration.expireHold(0) }
    }

    @Test
    fun `이미 취소 확정 만료된 예매는 다시 반환하지 않는다`() {
        val cancelled = reserve(listOf(seats[0]))
        cancel.cancelHold(CancelReservationHoldCommand(1, cancelled))
        val confirmed = reserve(listOf(seats[1]))
        makeDue(confirmed)
        jdbc.update("UPDATE reservations SET status = 'CONFIRMED', confirmed_at = held_at WHERE id = ?", confirmed)
        jdbc.update("UPDATE performance_seats SET status = 'RESERVED' WHERE reservation_id = ?", confirmed)
        val expired = reserve(listOf(seats[2]))
        listOf(cancelled, expired).forEach { makeDue(it) }
        assertTrue(expiration.expireHold(expired))

        listOf(cancelled, confirmed, expired).forEach { assertFalse(expiration.expireHold(it)) }
        assertEquals(1, allocated())
        assertEquals(confirmed, jdbc.queryForObject("SELECT reservation_id FROM performance_seats WHERE id = ?", Long::class.java, seats[1]))
        assertEquals("RESERVED", jdbc.queryForObject("SELECT status FROM performance_seats WHERE id = ?", String::class.java, seats[1]))
    }

    @Test
    fun `여러 작업자가 같은 만료 예매를 처리하면 한 번만 반환한다`() {
        val due = reserve(seats.take(2))
        reserve(listOf(seats[2]))
        makeDue(due)
        val results = race(List(8) { due })
        assertEquals(1, results.count { it })
        assertEquals("EXPIRED", status(due))
        assertEquals(1, allocated())
        assertEquals(1, count("SELECT count(*) FROM performance_seats WHERE status = 'HELD'"))
    }

    @Test
    fun `같은 사용자의 여러 예매를 동시에 만료해도 한도를 정확히 반환한다`() {
        val ids = seats.take(4).map { reserve(listOf(it)).also(::makeDue) }
        assertTrue(race(ids).all { it })
        assertEquals(0, allocated())
        assertEquals(0, count("SELECT count(*) FROM performance_seats WHERE status = 'HELD'"))
    }

    @Test
    fun `만료 후 재선점한 좌석은 이전 예매의 재처리로 해제되지 않는다`() {
        val old = reserve(listOf(seats[0]))
        makeDue(old)
        assertTrue(expiration.expireHold(old))
        val replacement = reserve(listOf(seats[0]))
        assertFalse(expiration.expireHold(old))
        assertEquals("HELD", status(replacement))
        assertEquals(replacement, jdbc.queryForObject("SELECT reservation_id FROM performance_seats WHERE id = ?", Long::class.java, seats[0]))
        assertEquals(1, allocated())
    }

    @Test
    fun `좌석 반환 실패 시 부분 해제와 한도와 만료 상태를 모두 롤백한다`() {
        val due = reserve(seats.take(2))
        val other = reserve(listOf(seats[2]))
        makeDue(due)
        jdbc.update("UPDATE performance_seats SET reservation_id = ? WHERE id = ?", other, seats[1])
        assertFailsWith<IllegalStateException> { expiration.expireHold(due) }
        assertEquals("HELD", status(due))
        assertEquals(3, allocated())
        assertEquals(3, count("SELECT count(*) FROM performance_seats WHERE status = 'HELD'"))
        assertEquals(other, jdbc.queryForObject("SELECT reservation_id FROM performance_seats WHERE id = ?", Long::class.java, seats[1]))
    }

    @Test
    fun `한도가 부족하거나 행이 없으면 만료 전환을 롤백한다`() {
        val due = reserve(seats.take(2))
        makeDue(due)
        jdbc.update("UPDATE concert_purchase_quotas SET allocated_quantity = 1")
        assertFailsWith<IllegalStateException> { expiration.expireHold(due) }
        assertEquals("HELD", status(due))
        assertEquals(1, allocated())
        jdbc.update("DELETE FROM concert_purchase_quotas")
        assertFailsWith<IllegalStateException> { expiration.expireHold(due) }
        assertEquals("HELD", status(due))
        assertEquals(2, count("SELECT count(*) FROM performance_seats WHERE status = 'HELD'"))
    }

    @Test
    fun `조회 크기를 넘어도 모두 처리하고 실패한 앞 예매는 다음 실행에서 재시도한다`() {
        val due = seats.take(5).mapIndexed { i, seat -> reserve(listOf(seat), i + 1L).also(::makeDue) }
        val active = reserve(listOf(seats[5]), 6)
        jdbc.update("UPDATE concert_purchase_quotas SET allocated_quantity = 0 WHERE user_id = 1")

        assertEquals(ExpireReservationHoldsResult(4, 0, 1), batch.expireHolds())
        assertEquals("HELD", status(due.first()))
        due.drop(1).forEach { assertEquals("EXPIRED", status(it)) }
        assertEquals("HELD", status(active))
        assertEquals(1, allocated())

        jdbc.update("UPDATE concert_purchase_quotas SET allocated_quantity = 1 WHERE user_id = 1")
        assertEquals(ExpireReservationHoldsResult(1, 0, 0), batch.expireHolds())
        assertEquals(ExpireReservationHoldsResult(0, 0, 0), batch.expireHolds())
        assertEquals(1, allocated())
    }

    @Test
    fun `대상 조회 후 다른 요청이 확정하면 만료 전환을 건너뛴다`() {
        val due = reserve(listOf(seats[0]))
        makeDue(due)
        assertEquals(listOf(due), candidates.findExpiredReservationIds(now, 0, 2))
        transition.beforeUpdate.set {
            TransactionTemplate(transactionManager).apply {
                propagationBehavior = TransactionDefinition.PROPAGATION_REQUIRES_NEW
            }.executeWithoutResult {
                jdbc.update("UPDATE reservations SET status = 'CONFIRMED', confirmed_at = held_at WHERE id = ?", due)
                jdbc.update("UPDATE performance_seats SET status = 'RESERVED' WHERE reservation_id = ?", due)
            }
        }
        assertEquals(ExpireReservationHoldsResult(0, 1, 0), batch.expireHolds())
        transition.beforeUpdate.set(null)
        assertEquals("CONFIRMED", status(due))
        assertEquals(1, allocated())
    }

    @Test
    fun `만료 직전 접수한 취소와 만료가 경합해도 한 번만 반환한다`() {
        val due = reserve(seats.take(2))
        reserve(listOf(seats[2]))
        makeDue(due)
        val barrier = CyclicBarrier(2)
        transition.beforeUpdate.set { barrier.await(10, TimeUnit.SECONDS) }
        // 취소는 만료 직전 시각에 접수되고, 실제 UPDATE는 만료 작업과 동시에 진행한다.
        val cancellation = CancelReservationHoldService(
            adapter,
            CancelHeldReservationPort { id, user, time ->
                barrier.await(10, TimeUnit.SECONDS)
                adapter.cancelHeld(id, user, time)
            },
            quotaRelease, seatRelease, Clock.fixed(now.minusSeconds(1), ZoneOffset.UTC),
        )
        val executor = Executors.newFixedThreadPool(2)
        try {
            val expiry = executor.submit<Boolean> { expiration.expireHold(due) }
            val cancelled = executor.submit<Result<Unit>> {
                runCatching {
                    TransactionTemplate(transactionManager).executeWithoutResult {
                        cancellation.cancelHold(CancelReservationHoldCommand(1, due))
                    }
                }
            }
            val expired = expiry.get(30, TimeUnit.SECONDS)
            val cancellationResult = cancelled.get(30, TimeUnit.SECONDS)
            assertEquals(1, listOf(expired, cancellationResult.isSuccess).count { it })
            if (expired) assertTrue(cancellationResult.exceptionOrNull() is ReservationRuleViolation)
            assertEquals(if (expired) "EXPIRED" else "CANCELLED", status(due))
            assertEquals(1, allocated())
            assertEquals(1, count("SELECT count(*) FROM performance_seats WHERE status = 'HELD'"))
        } finally {
            transition.beforeUpdate.set(null)
            executor.shutdownNow()
            assertTrue(executor.awaitTermination(10, TimeUnit.SECONDS))
        }
    }

    @ParameterizedTest
    @ValueSource(strings = ["reservation", "quota", "seat"])
    fun `예매 한도 좌석 잠금이 오래 유지돼도 해당 예매만 롤백하고 다음 예매를 만료한다`(resource: String) {
        val first = reserve(seats.take(2), 1).also(::makeDue)
        val second = reserve(listOf(seats[2]), 2).also(::makeDue)
        val locked = java.util.concurrent.CountDownLatch(1)
        val release = java.util.concurrent.CountDownLatch(1)
        val executor = Executors.newFixedThreadPool(2)
        try {
            val holder = executor.submit {
                TransactionTemplate(transactionManager).executeWithoutResult {
                    when (resource) {
                        "reservation" -> jdbc.queryForList("SELECT id FROM reservations WHERE id = ? FOR UPDATE", first)
                        "quota" -> jdbc.queryForList("SELECT id FROM concert_purchase_quotas WHERE user_id = 1 FOR UPDATE")
                        "seat" -> jdbc.queryForList("SELECT id FROM performance_seats WHERE id = ? FOR UPDATE", seats[1])
                        else -> error("알 수 없는 잠금 대상")
                    }
                    locked.countDown()
                    check(release.await(15, TimeUnit.SECONDS))
                }
            }
            assertTrue(locked.await(5, TimeUnit.SECONDS))
            val worker = executor.submit<ExpireReservationHoldsResult> { batch.expireHolds() }
            // 잠금을 풀기 전에도 첫 예매를 건너뛰고 두 번째 예매 처리를 완료해야 한다.
            assertEquals(ExpireReservationHoldsResult(1, 0, 1), worker.get(5, TimeUnit.SECONDS))
            assertEquals("HELD", status(first))
            assertEquals("EXPIRED", status(second))
            assertEquals(2, allocated())
            assertEquals(2, count("SELECT count(*) FROM performance_seats WHERE reservation_id = ? AND status = 'HELD'", first))
            assertEquals(1, count("SELECT count(*) FROM performance_seats WHERE id = ? AND status = 'AVAILABLE' AND reservation_id IS NULL", seats[2]))

            release.countDown()
            holder.get(5, TimeUnit.SECONDS)
            assertEquals(ExpireReservationHoldsResult(1, 0, 0), batch.expireHolds())
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
        val barrier = CyclicBarrier(ids.size)
        transition.beforeUpdate.set { barrier.await(10, TimeUnit.SECONDS) }
        val executor = Executors.newFixedThreadPool(ids.size)
        return try {
            ids.map { id -> executor.submit<Boolean> { expiration.expireHold(id) } }.map { it.get(30, TimeUnit.SECONDS) }
        } finally {
            transition.beforeUpdate.set(null)
            executor.shutdownNow()
            assertTrue(executor.awaitTermination(10, TimeUnit.SECONDS))
        }
    }

    private fun reserve(selected: List<Long>, user: Long = 1) = create.create(CreateReservationCommand(user, performanceId, selected)).reservationId
    private fun makeDue(id: Long) {
        jdbc.update("UPDATE reservations SET held_at = ?, expires_at = ? WHERE id = ?", Timestamp.from(now.minusSeconds(300)), Timestamp.from(now), id)
    }
    private fun status(id: Long) = jdbc.queryForObject("SELECT status FROM reservations WHERE id = ?", String::class.java, id)
    private fun allocated() = count("SELECT coalesce(sum(allocated_quantity), 0) FROM concert_purchase_quotas")
    private fun count(sql: String, vararg args: Any) = jdbc.queryForObject(sql, Int::class.java, *args)!!
    private fun id(sql: String, vararg args: Any) = jdbc.queryForObject(sql, Long::class.java, *args)!!

    @TestConfiguration(proxyBeanMethods = false)
    class Configuration {
        @Bean @Primary
        fun expirationClock(): Clock = Clock.fixed(Instant.parse("2026-11-04T00:00:00Z"), ZoneOffset.UTC)
        @Bean @Primary
        fun coordinatedExpiration(adapter: ReservationPersistenceAdapter) = CoordinatedExpiration(adapter)
    }

    class CoordinatedExpiration(private val delegate: ReservationPersistenceAdapter) : ExpireHeldReservationPort {
        val beforeUpdate = AtomicReference<(() -> Unit)?>(null)
        override fun expireHeld(reservationId: Long, now: Instant): Boolean {
            beforeUpdate.get()?.invoke()
            return delegate.expireHeld(reservationId, now)
        }
    }
}
