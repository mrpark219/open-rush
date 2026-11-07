package me.park.openrush.reservation.adapter.output.persistence

import me.park.openrush.TestcontainersConfiguration
import me.park.openrush.reservation.application.port.input.CancelReservationHoldCommand
import me.park.openrush.reservation.application.port.input.CancelReservationHoldUseCase
import me.park.openrush.reservation.application.port.input.ConfirmReservationCommand
import me.park.openrush.reservation.application.port.input.ConfirmReservationUseCase
import me.park.openrush.reservation.application.port.input.CreateReservationCommand
import me.park.openrush.reservation.application.port.input.CreateReservationUseCase
import me.park.openrush.reservation.application.port.input.ExpireReservationHoldUseCase
import me.park.openrush.reservation.application.port.output.ConfirmHeldReservationPort
import me.park.openrush.reservation.domain.exception.ReservationRuleViolation
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.EnumSource
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.springframework.context.annotation.Primary
import org.springframework.jdbc.core.JdbcTemplate
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.concurrent.CountDownLatch
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

@Import(TestcontainersConfiguration::class, ConfirmReservationTest.Configuration::class)
@SpringBootTest(properties = ["reservation.expiration.enabled=false"])
class ConfirmReservationTest {
    @Autowired private lateinit var create: CreateReservationUseCase
    @Autowired private lateinit var confirmation: ConfirmReservationUseCase
    @Autowired private lateinit var cancellation: CancelReservationHoldUseCase
    @Autowired private lateinit var expiration: ExpireReservationHoldUseCase
    @Autowired private lateinit var transition: CoordinatedConfirmation
    @Autowired private lateinit var clock: AdjustableClock
    @Autowired private lateinit var jdbc: JdbcTemplate
    private lateinit var seats: List<Long>
    private var performanceId = 0L

    @BeforeEach
    fun setUp() {
        clock.now = heldAt
        transition.beforeUpdate = null
        jdbc.execute("TRUNCATE concerts CASCADE")
        val concertId = id("INSERT INTO concerts(title, description, max_tickets_per_user) VALUES ('공연', '', 4) RETURNING id")
        performanceId = id("""INSERT INTO performances(concert_id, venue, starts_at, booking_opens_at, booking_closes_at)
            VALUES (?, '공연장', '2026-11-06T00:00:00Z', '2026-11-03T00:00:00Z', '2026-11-05T00:00:00Z') RETURNING id""", concertId)
        seats = (1..3).map { number ->
            id("""INSERT INTO performance_seats(performance_id, seat_number, price, status)
                VALUES (?, ?, 50000, 'AVAILABLE') RETURNING id""", performanceId, "A$number")
        }
    }

    @Test
    fun `기한 직전 확정하면 좌석과 한도를 유지하고 이후 취소와 만료를 거절한다`() {
        val reservation = create.create(CreateReservationCommand(1, performanceId, seats.take(2)))
        clock.now = reservation.expiresAt.minusNanos(1)
        confirmation.confirm(ConfirmReservationCommand(1, reservation.reservationId))

        assertEquals("CONFIRMED", status(reservation.reservationId))
        assertEquals(reservation.expiresAt.minusNanos(1_000), confirmedAt(reservation.reservationId))
        assertEquals(2, seatsOwnedBy(reservation.reservationId, "RESERVED"))
        assertEquals(2, allocated())

        assertFailsWith<ReservationRuleViolation> { cancellation.cancelHold(CancelReservationHoldCommand(1, reservation.reservationId)) }
        clock.now = reservation.expiresAt
        assertFalse(expiration.expireHold(reservation.reservationId))
        assertEquals(2, seatsOwnedBy(reservation.reservationId, "RESERVED"))
        assertEquals(2, allocated())
    }

    @Test
    fun `만료 시각에 도달한 선점은 확정할 수 없다`() {
        val reservation = create.create(CreateReservationCommand(1, performanceId, seats.take(2)))
        clock.now = reservation.expiresAt

        assertFailsWith<ReservationRuleViolation> { confirmation.confirm(ConfirmReservationCommand(1, reservation.reservationId)) }
        assertEquals("HELD", status(reservation.reservationId))
        assertNull(confirmedAt(reservation.reservationId))
        assertEquals(2, seatsOwnedBy(reservation.reservationId, "HELD"))
        assertEquals(2, allocated())
    }

    @Test
    fun `다른 사용자와 존재하지 않는 예매는 확정할 수 없다`() {
        val reservation = create.create(CreateReservationCommand(1, performanceId, seats.take(2)))
        assertFailsWith<ReservationRuleViolation> { confirmation.confirm(ConfirmReservationCommand(2, reservation.reservationId)) }
        assertFailsWith<ReservationRuleViolation> { confirmation.confirm(ConfirmReservationCommand(1, Long.MAX_VALUE)) }
        assertEquals("HELD", status(reservation.reservationId))
        assertEquals(2, seatsOwnedBy(reservation.reservationId, "HELD"))
        assertEquals(2, allocated())
    }

    @Test
    fun `좌석 하나의 선점 소유자가 다르면 예매와 나머지 좌석의 확정도 롤백한다`() {
        val reservation = create.create(CreateReservationCommand(1, performanceId, seats.take(2)))
        val other = create.create(CreateReservationCommand(2, performanceId, listOf(seats[2])))
        jdbc.update("UPDATE performance_seats SET reservation_id = ? WHERE id = ?", other.reservationId, seats[1])

        assertFailsWith<IllegalStateException> { confirmation.confirm(ConfirmReservationCommand(1, reservation.reservationId)) }
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
        val reservation = create.create(CreateReservationCommand(1, performanceId, seats.take(2)))
        val validated = CountDownLatch(1)
        val proceed = CountDownLatch(1)
        transition.beforeUpdate = {
            validated.countDown()
            check(proceed.await(10, TimeUnit.SECONDS))
        }
        val executor = Executors.newSingleThreadExecutor()
        try {
            val pendingConfirmation = executor.submit<Throwable?> {
                runCatching { confirmation.confirm(ConfirmReservationCommand(1, reservation.reservationId)) }.exceptionOrNull()
            }
            assertTrue(validated.await(5, TimeUnit.SECONDS))
            // 확정 트랜잭션이 상태를 읽고 나면 다른 트랜잭션이 먼저 상태를 전환한다.
            when (competing) {
                CompetingTransition.CANCELLED -> cancellation.cancelHold(CancelReservationHoldCommand(1, reservation.reservationId))
                CompetingTransition.EXPIRED -> {
                    clock.now = reservation.expiresAt
                    assertTrue(expiration.expireHold(reservation.reservationId))
                }
            }
            proceed.countDown()
            assertTrue(pendingConfirmation.get(5, TimeUnit.SECONDS) is ReservationRuleViolation)
            assertEquals(competing.name, status(reservation.reservationId))
            assertNull(confirmedAt(reservation.reservationId))
            assertEquals(0, allocated())
            assertEquals(3, count("SELECT count(*) FROM performance_seats WHERE status = 'AVAILABLE' AND reservation_id IS NULL"))
        } finally {
            proceed.countDown()
            transition.beforeUpdate = null
            executor.shutdownNow()
            assertTrue(executor.awaitTermination(10, TimeUnit.SECONDS))
        }
    }

    @Test
    fun `동시에 같은 예매를 확정해도 한 번만 전환한다`() {
        val reservation = create.create(CreateReservationCommand(1, performanceId, seats.take(2)))
        val barrier = CyclicBarrier(2)
        transition.beforeUpdate = { barrier.await(5, TimeUnit.SECONDS) }
        val executor = Executors.newFixedThreadPool(2)
        try {
            val results = List(2) {
                executor.submit<Throwable?> {
                    runCatching { confirmation.confirm(ConfirmReservationCommand(1, reservation.reservationId)) }.exceptionOrNull()
                }
            }.map { it.get(10, TimeUnit.SECONDS) }
            assertEquals(1, results.count { it == null })
            assertTrue(results.single { it != null } is ReservationRuleViolation)
            assertEquals("CONFIRMED", status(reservation.reservationId))
            assertEquals(2, seatsOwnedBy(reservation.reservationId, "RESERVED"))
            assertEquals(2, allocated())
        } finally {
            transition.beforeUpdate = null
            executor.shutdownNow()
            assertTrue(executor.awaitTermination(10, TimeUnit.SECONDS))
        }
    }

    enum class CompetingTransition { CANCELLED, EXPIRED }

    private fun status(id: Long) = jdbc.queryForObject("SELECT status FROM reservations WHERE id = ?", String::class.java, id)
    private fun confirmedAt(id: Long) = jdbc.queryForObject("SELECT confirmed_at FROM reservations WHERE id = ?", java.sql.Timestamp::class.java, id)?.toInstant()
    private fun seatsOwnedBy(id: Long, status: String) = count("SELECT count(*) FROM performance_seats WHERE reservation_id = ? AND status = ?", id, status)
    private fun allocated() = count("SELECT coalesce(sum(allocated_quantity), 0) FROM concert_purchase_quotas")
    private fun count(sql: String, vararg args: Any) = jdbc.queryForObject(sql, Int::class.java, *args)!!
    private fun id(sql: String, vararg args: Any) = jdbc.queryForObject(sql, Long::class.java, *args)!!

    @TestConfiguration(proxyBeanMethods = false)
    class Configuration {
        @Bean @Primary
        fun confirmationClock() = AdjustableClock()
        @Bean @Primary
        fun coordinatedConfirmation(adapter: ReservationPersistenceAdapter) = CoordinatedConfirmation(adapter)
    }

    class AdjustableClock : Clock() {
        @Volatile var now: Instant = heldAt
        override fun instant(): Instant = now
        override fun getZone(): ZoneId = ZoneOffset.UTC
        override fun withZone(zone: ZoneId): Clock = Clock.fixed(now, zone)
    }

    class CoordinatedConfirmation(private val delegate: ConfirmHeldReservationPort) : ConfirmHeldReservationPort {
        @Volatile var beforeUpdate: (() -> Unit)? = null
        override fun confirmHeld(reservationId: Long, userId: Long, now: Instant) {
            beforeUpdate?.invoke()
            delegate.confirmHeld(reservationId, userId, now)
        }
    }

    companion object {
        private val heldAt = Instant.parse("2026-11-04T00:00:00Z")
    }
}
