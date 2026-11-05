package me.park.openrush.reservation.adapter.output.persistence

import me.park.openrush.TestcontainersConfiguration
import me.park.openrush.reservation.application.port.input.CancelReservationHoldCommand
import me.park.openrush.reservation.application.port.input.CancelReservationHoldUseCase
import me.park.openrush.reservation.application.port.input.CreateReservationCommand
import me.park.openrush.reservation.application.port.input.CreateReservationUseCase
import me.park.openrush.reservation.application.port.output.LoadReservationPort
import me.park.openrush.reservation.domain.Reservation
import me.park.openrush.reservation.domain.exception.ReservationRuleViolation
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
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
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

@Import(TestcontainersConfiguration::class, CancelReservationHoldTest.Configuration::class)
@SpringBootTest(properties = ["spring.datasource.hikari.maximum-pool-size=16"])
class CancelReservationHoldTest {
    @Autowired private lateinit var create: CreateReservationUseCase
    @Autowired private lateinit var holdCancellation: CancelReservationHoldUseCase
    @Autowired private lateinit var jdbc: JdbcTemplate
    @Autowired private lateinit var loader: CoordinatedReservationLoader
    @Autowired private lateinit var transactionManager: PlatformTransactionManager
    private var performanceId = 0L
    private lateinit var seats: List<Long>
    private val now = Instant.parse("2026-11-04T00:00:00Z")

    @BeforeEach
    fun setUp() {
        loader.afterRead.set(null)
        jdbc.execute("TRUNCATE concerts CASCADE")
        val concertId = id("INSERT INTO concerts(title, description, max_tickets_per_user) VALUES ('공연', '', 4) RETURNING id")
        performanceId = id("""INSERT INTO performances(concert_id, venue, starts_at, booking_opens_at, booking_closes_at)
            VALUES (?, '공연장', '2026-11-06T00:00:00Z', '2026-11-03T00:00:00Z', '2026-11-05T00:00:00Z') RETURNING id""", concertId)
        seats = (1..4).map {
            id("""INSERT INTO performance_seats(performance_id, seat_number, price, status)
                VALUES (?, ?, 50000, 'AVAILABLE') RETURNING id""", performanceId, "A$it")
        }
    }

    @Test
    fun `예매 전체를 취소하면 좌석과 소유 정보를 해제하고 해당 수량만 한도를 반환한다`() {
        val first = reserve(seats.take(2))
        val second = reserve(seats.drop(2).take(1))
        jdbc.update("UPDATE reservations SET modified_at = '2000-01-01T00:00:00Z'")
        jdbc.update("UPDATE performance_seats SET modified_at = '2000-01-01T00:00:00Z'")
        jdbc.update("UPDATE concert_purchase_quotas SET modified_at = '2000-01-01T00:00:00Z'")

        holdCancellation.cancelHold(CancelReservationHoldCommand(1, first))

        assertEquals("CANCELLED", status(first))
        assertEquals("HELD", status(second))
        assertEquals(1, allocated())
        assertEquals(2, count("SELECT count(*) FROM performance_seats WHERE status = 'AVAILABLE' AND reservation_id IS NULL AND id IN (?, ?)", seats[0], seats[1]))
        assertEquals(1, count("SELECT count(*) FROM performance_seats WHERE status = 'HELD' AND reservation_id = ?", second))
        assertEquals(2, count("SELECT count(*) FROM reservation_items WHERE reservation_id = ?", first))
        assertEquals(1, count("SELECT count(*) FROM reservations WHERE id = ? AND modified_at > '2000-01-01'", first))
        assertEquals(2, count("SELECT count(*) FROM performance_seats WHERE id IN (?, ?) AND modified_at > '2000-01-01'", seats[0], seats[1]))
        assertEquals(1, count("SELECT count(*) FROM concert_purchase_quotas WHERE modified_at > '2000-01-01'"))
    }

    @Test
    fun `타인의 예매와 존재하지 않는 예매는 취소할 수 없다`() {
        val reservationId = reserve(seats.take(1))
        assertFailsWith<ReservationRuleViolation> { holdCancellation.cancelHold(CancelReservationHoldCommand(2, reservationId)) }
        assertFailsWith<ReservationRuleViolation> { holdCancellation.cancelHold(CancelReservationHoldCommand(1, Long.MAX_VALUE)) }
        assertHeld(reservationId, 1)
    }

    @Test
    fun `만료 경계와 선점 시각 이전에는 취소할 수 없다`() {
        val reservationId = reserve(seats.take(1))
        jdbc.update("UPDATE reservations SET held_at = ?, expires_at = ? WHERE id = ?",
            java.sql.Timestamp.from(now.minusSeconds(300)), java.sql.Timestamp.from(now), reservationId)
        assertFailsWith<ReservationRuleViolation> { holdCancellation.cancelHold(CancelReservationHoldCommand(1, reservationId)) }
        assertHeld(reservationId, 1)
        jdbc.update("UPDATE reservations SET held_at = ?, expires_at = ? WHERE id = ?",
            java.sql.Timestamp.from(now.plusSeconds(1)), java.sql.Timestamp.from(now.plusSeconds(300)), reservationId)
        assertFailsWith<ReservationRuleViolation> { holdCancellation.cancelHold(CancelReservationHoldCommand(1, reservationId)) }
        assertHeld(reservationId, 1)
    }

    @Test
    fun `확정 취소 만료 상태에서는 자원을 반환하지 않는다`() {
        val reservationId = reserve(seats.take(1))
        listOf("CONFIRMED", "CANCELLED", "EXPIRED").forEach { status ->
            jdbc.update("UPDATE reservations SET status = ?, confirmed_at = ? WHERE id = ?",
                status, if (status == "CONFIRMED") java.sql.Timestamp.from(now) else null, reservationId)
            assertFailsWith<ReservationRuleViolation> { holdCancellation.cancelHold(CancelReservationHoldCommand(1, reservationId)) }
            assertEquals(status, status(reservationId))
            assertEquals(1, allocated())
            assertEquals(1, count("SELECT count(*) FROM performance_seats WHERE status = 'HELD'"))
        }
    }

    @Test
    fun `동시에 취소한 요청 중 하나만 성공하고 한도는 한 번만 반환한다`() {
        val reservationId = reserve(seats.take(2))
        reserve(seats.drop(2).take(1))
        val results = race(List(8) { reservationId })
        assertEquals(1, results.count { it.isSuccess })
        results.filter { it.isFailure }.forEach { assertTrue(it.exceptionOrNull() is ReservationRuleViolation) }
        assertEquals("CANCELLED", status(reservationId))
        assertEquals(1, allocated())
        assertEquals(1, count("SELECT count(*) FROM performance_seats WHERE status = 'HELD'"))
    }

    @Test
    fun `취소 후 재선점한 좌석은 이전 예매의 중복 취소로 해제되지 않는다`() {
        val old = reserve(seats.take(1))
        holdCancellation.cancelHold(CancelReservationHoldCommand(1, old))
        val replacement = reserve(seats.take(1))
        assertFailsWith<ReservationRuleViolation> { holdCancellation.cancelHold(CancelReservationHoldCommand(1, old)) }
        assertHeld(replacement, 1)
        assertEquals(replacement, jdbc.queryForObject("SELECT reservation_id FROM performance_seats WHERE id = ?", Long::class.java, seats.first()))
    }

    @Test
    fun `조회 후 예매가 확정되면 조건부 상태 전환이 실패하고 자원은 반환되지 않는다`() {
        val reservationId = reserve(seats.take(1))
        loader.afterRead.set {
            TransactionTemplate(transactionManager).apply {
                propagationBehavior = TransactionDefinition.PROPAGATION_REQUIRES_NEW
            }.executeWithoutResult {
                jdbc.update("UPDATE reservations SET status = 'CONFIRMED', confirmed_at = ? WHERE id = ?",
                    java.sql.Timestamp.from(now), reservationId)
            }
        }
        assertFailsWith<ReservationRuleViolation> { holdCancellation.cancelHold(CancelReservationHoldCommand(1, reservationId)) }
        loader.afterRead.set(null)
        assertEquals("CONFIRMED", status(reservationId))
        assertEquals(1, allocated())
        assertEquals(1, count("SELECT count(*) FROM performance_seats WHERE status = 'HELD'"))
    }

    @Test
    fun `일부 좌석이 다른 예매 소유라면 부분 해제와 상태 변경과 한도 반환을 모두 롤백한다`() {
        val reservationId = reserve(seats.take(2))
        val other = reserve(seats.drop(2).take(1))
        jdbc.update("UPDATE performance_seats SET reservation_id = ? WHERE id = ?", other, seats[1])
        assertFailsWith<IllegalStateException> { holdCancellation.cancelHold(CancelReservationHoldCommand(1, reservationId)) }
        assertHeld(reservationId, 3)
        assertEquals(other, jdbc.queryForObject("SELECT reservation_id FROM performance_seats WHERE id = ?", Long::class.java, seats[1]))
    }

    @Test
    fun `한도가 부족하거나 행이 없으면 상태 전환도 롤백한다`() {
        val reservationId = reserve(seats.take(2))
        jdbc.update("UPDATE concert_purchase_quotas SET allocated_quantity = 1")
        assertFailsWith<IllegalStateException> { holdCancellation.cancelHold(CancelReservationHoldCommand(1, reservationId)) }
        assertHeld(reservationId, 1)
        jdbc.update("DELETE FROM concert_purchase_quotas")
        assertFailsWith<IllegalStateException> { holdCancellation.cancelHold(CancelReservationHoldCommand(1, reservationId)) }
        assertHeld(reservationId, 0)
    }

    @Test
    fun `같은 사용자의 서로 다른 예매를 동시에 취소해도 한도를 정확히 반환한다`() {
        val reservations = seats.map { reserve(listOf(it)) }
        val results = race(reservations)
        results.forEach { it.getOrThrow() }
        reservations.forEach { assertEquals("CANCELLED", status(it)) }
        assertEquals(0, allocated())
        assertEquals(4, count("SELECT count(*) FROM performance_seats WHERE status = 'AVAILABLE' AND reservation_id IS NULL"))
        reserve(seats)
        assertEquals(4, allocated())
    }

    private fun race(reservationIds: List<Long>): List<Result<Unit>> {
        val barrier = CyclicBarrier(reservationIds.size)
        loader.afterRead.set { barrier.await(10, TimeUnit.SECONDS) }
        val executor = Executors.newFixedThreadPool(reservationIds.size)
        return try {
            reservationIds.map { reservationId ->
                executor.submit<Result<Unit>> { runCatching { holdCancellation.cancelHold(CancelReservationHoldCommand(1, reservationId)) } }
            }.map { it.get(30, TimeUnit.SECONDS) }
        } finally {
            loader.afterRead.set(null)
            executor.shutdownNow()
            assertTrue(executor.awaitTermination(10, TimeUnit.SECONDS))
        }
    }

    private fun reserve(selected: List<Long>) = create.create(CreateReservationCommand(1, performanceId, selected)).reservationId
    private fun status(id: Long) = jdbc.queryForObject("SELECT status FROM reservations WHERE id = ?", String::class.java, id)
    private fun allocated() = count("SELECT coalesce(sum(allocated_quantity), 0) FROM concert_purchase_quotas")
    private fun assertHeld(id: Long, quota: Int) {
        assertEquals("HELD", status(id))
        assertEquals(quota, allocated())
        assertEquals(0, count("SELECT count(*) FROM performance_seats WHERE status != 'HELD' AND id IN (SELECT performance_seat_id FROM reservation_items WHERE reservation_id = ?)", id))
    }
    private fun count(sql: String, vararg args: Any) = jdbc.queryForObject(sql, Int::class.java, *args)!!
    private fun id(sql: String, vararg args: Any) = jdbc.queryForObject(sql, Long::class.java, *args)!!

    @TestConfiguration(proxyBeanMethods = false)
    class Configuration {
        @Bean @Primary
        fun cancellationClock(): Clock = Clock.fixed(Instant.parse("2026-11-04T00:00:00Z"), ZoneOffset.UTC)
        @Bean @Primary
        fun coordinatedReservationLoader(adapter: ReservationPersistenceAdapter) = CoordinatedReservationLoader(adapter)
    }

    class CoordinatedReservationLoader(private val delegate: ReservationPersistenceAdapter) : LoadReservationPort {
        val afterRead = AtomicReference<(() -> Unit)?>(null)
        override fun findReservation(reservationId: Long): Reservation? {
            val reservation = delegate.findReservation(reservationId)
            afterRead.get()?.invoke()
            return reservation
        }
    }
}
