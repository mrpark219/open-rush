package me.park.openrush.reservation.adapter.output.persistence

import jakarta.persistence.EntityManager
import me.park.openrush.TestcontainersConfiguration
import me.park.openrush.reservation.application.port.input.CreateReservationCommand
import me.park.openrush.reservation.application.port.input.CreateReservationUseCase
import me.park.openrush.reservation.application.port.output.AllocatePurchaseQuotaPort
import me.park.openrush.reservation.application.port.output.AllocateQuotaCommand
import me.park.openrush.reservation.application.port.output.HoldPerformanceSeatsPort
import me.park.openrush.reservation.application.port.output.HoldSeatsCommand
import me.park.openrush.reservation.domain.Reservation
import me.park.openrush.reservation.domain.exception.ReservationRuleViolation
import me.park.openrush.reservation.domain.seat.PerformanceSeat
import me.park.openrush.reservation.domain.seat.SeatStatus
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate
import java.time.Instant
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@Import(TestcontainersConfiguration::class)
@SpringBootTest
class ConditionalUpdateReservationTest @Autowired constructor(
    private val reservations: CreateReservationUseCase,
    private val persistence: ReservationPersistenceAdapter,
    private val seats: HoldPerformanceSeatsPort,
    private val quotas: AllocatePurchaseQuotaPort,
    private val jdbc: JdbcTemplate,
    private val entityManager: EntityManager,
    transactionManager: PlatformTransactionManager,
) {
    private val transaction = TransactionTemplate(transactionManager)
    private var concertId = 0L
    private var performanceId = 0L
    private var seatIds = emptyList<Long>()

    @BeforeEach
    fun setUp() {
        jdbc.execute("TRUNCATE reservation_items, performance_seats, reservations, concert_purchase_quotas, performances, concerts RESTART IDENTITY CASCADE")
        concertId = insert("INSERT INTO concerts (title, description, max_tickets_per_user) VALUES ('공연', '', 4) RETURNING id")
        performanceId = insert("""
            INSERT INTO performances (concert_id, venue, starts_at, booking_opens_at, booking_closes_at)
            VALUES ($concertId, '공연장', CURRENT_TIMESTAMP + INTERVAL '2 days',
                    CURRENT_TIMESTAMP - INTERVAL '1 day', CURRENT_TIMESTAMP + INTERVAL '1 day') RETURNING id
        """)
        seatIds = (1..16).map {
            insert("INSERT INTO performance_seats (performance_id, seat_number, price, status) VALUES ($performanceId, 'A$it', 50000, 'AVAILABLE') RETURNING id")
        }
    }

    @Test
    fun `동일 좌석 동시 예매는 한 건만 성공한다`() {
        val results = concurrently { index ->
            reservations.create(CreateReservationCommand(index + 1L, performanceId, listOf(seatIds.first())))
        }

        assertEquals(1, results.count { it.isSuccess })
        results.filter { it.isFailure }.forEach { assertTrue(it.exceptionOrNull() is ReservationRuleViolation) }
        assertEquals(1, count("reservations"))
        assertEquals(1, count("reservation_items"))
        assertEquals(1, quantity())
        assertEquals(1, count("performance_seats", "status = 'HELD'"))
    }

    @Test
    fun `구매 한도 최초 생성과 동시 확보에도 네 매까지만 성공한다`() {
        assertQuotaCompetition()
    }

    @Test
    fun `이미 존재하는 구매 한도도 동시 요청으로 초과하지 않는다`() {
        jdbc.update("INSERT INTO concert_purchase_quotas (user_id, concert_id, allocated_quantity) VALUES (1, ?, 0)", concertId)
        assertQuotaCompetition()
    }

    @Test
    fun `다른 회차를 동시에 예매해도 공연별 구매 한도를 공유한다`() {
        val otherPerformance = insert("""
            INSERT INTO performances (concert_id, venue, starts_at, booking_opens_at, booking_closes_at)
            SELECT concert_id, venue, starts_at, booking_opens_at, booking_closes_at FROM performances
            WHERE id = $performanceId RETURNING id
        """)
        seatIds.drop(8).forEach { jdbc.update("UPDATE performance_seats SET performance_id = ? WHERE id = ?", otherPerformance, it) }
        assertQuotaCompetition { if (it < 8) performanceId else otherPerformance }
    }

    @Test
    fun `좌석 일부 갱신 후 실패하면 앞선 선점과 구매 한도도 롤백한다`() {
        assertFailsWith<ReservationRuleViolation> {
            transaction.executeWithoutResult {
                val performance = requireNotNull(persistence.findPerformance(performanceId))
                val selected = persistence.findSeats(performanceId, seatIds.take(2))
                val reservation = Reservation.create(performance, 1, selected, Instant.now())

                // 최초 조회 후 다른 트랜잭션이 두 번째 좌석을 먼저 선점한다.
                reservations.create(CreateReservationCommand(2, performanceId, listOf(seatIds[1])))

                quotas.allocate(AllocateQuotaCommand.from(reservation))
                val saved = persistence.save(reservation)
                seats.hold(HoldSeatsCommand.from(saved).copy(seatIds = seatIds.take(2).reversed()))
            }
        }

        assertEquals("AVAILABLE", jdbc.queryForObject("SELECT status FROM performance_seats WHERE id = ?", String::class.java, seatIds[0]))
        assertEquals(0, count("concert_purchase_quotas", "user_id = 1"))
        assertEquals(1, quantity())
        assertEquals(1, count("reservations"))
    }

    @Test
    fun `없는 좌석이나 다른 회차 좌석이 포함되면 선점하지 않는다`() {
        val owner = reservations.create(CreateReservationCommand(2, performanceId, listOf(seatIds.last())))
        listOf(HoldSeatsCommand(owner.reservationId, performanceId, listOf(seatIds[0], Long.MAX_VALUE)),
            HoldSeatsCommand(owner.reservationId, Long.MAX_VALUE, listOf(seatIds[0]))).forEach { command ->
            assertFailsWith<ReservationRuleViolation> {
                transaction.executeWithoutResult { seats.hold(command) }
            }
        }
        assertEquals(1, count("performance_seats", "status = 'HELD'"))
    }

    @Test
    fun `벌크 갱신 뒤 재조회는 선점 상태와 수정 시각을 반환한다`() {
        val owner = reservations.create(CreateReservationCommand(2, performanceId, listOf(seatIds.last())))
        jdbc.update("UPDATE performance_seats SET modified_at = '2000-01-01T00:00:00Z' WHERE id = ?", seatIds[0])
        transaction.executeWithoutResult {
            val before = entityManager.find(PerformanceSeat::class.java, seatIds[0])
            seats.hold(HoldSeatsCommand(owner.reservationId, performanceId, listOf(seatIds[0])))
            assertFalse(entityManager.contains(before))
            val after = entityManager.find(PerformanceSeat::class.java, seatIds[0])
            assertEquals(SeatStatus.HELD, after.status)
            assertEquals(owner.reservationId, after.reservationId)
            assertTrue(requireNotNull(after.modifiedAt) > requireNotNull(before.modifiedAt))
        }
    }

    @Test
    fun `이미 확보한 수량을 합산해 구매 한도 초과를 거절한다`() {
        transaction.executeWithoutResult { quotas.allocate(AllocateQuotaCommand(1, concertId, 3)) }
        assertFailsWith<ReservationRuleViolation> {
            transaction.executeWithoutResult { quotas.allocate(AllocateQuotaCommand(1, concertId, 2)) }
        }
        assertEquals(3, quantity())
        transaction.executeWithoutResult { quotas.allocate(AllocateQuotaCommand(1, concertId, 1)) }
        assertEquals(4, quantity())
    }

    private fun assertQuotaCompetition(performanceFor: (Int) -> Long = { performanceId }) {
        val results = concurrently { index ->
            reservations.create(CreateReservationCommand(1, performanceFor(index), listOf(seatIds[index])))
        }
        assertEquals(4, results.count { it.isSuccess })
        results.filter { it.isFailure }.forEach { assertTrue(it.exceptionOrNull() is ReservationRuleViolation, it.exceptionOrNull().toString()) }
        assertEquals(1, count("concert_purchase_quotas"))
        assertEquals(4, quantity())
        assertEquals(4, count("reservations"))
        assertEquals(4, count("reservation_items"))
        assertEquals(4, count("performance_seats", "status = 'HELD'"))
    }

    private fun <T> concurrently(action: (Int) -> T): List<Result<T>> {
        val executor = Executors.newFixedThreadPool(16)
        val ready = CountDownLatch(16)
        val start = CountDownLatch(1)
        try {
            val futures = (0 until 16).map { index ->
                executor.submit<Result<T>> {
                    ready.countDown()
                    check(start.await(10, TimeUnit.SECONDS))
                    runCatching { action(index) }
                }
            }
            assertTrue(ready.await(10, TimeUnit.SECONDS))
            start.countDown()
            return futures.map { it.get(30, TimeUnit.SECONDS) }
        } finally {
            start.countDown()
            executor.shutdownNow()
        }
    }

    private fun insert(sql: String): Long = requireNotNull(jdbc.queryForObject(sql, Long::class.java))
    private fun count(table: String, condition: String = "TRUE"): Int =
        requireNotNull(jdbc.queryForObject("SELECT COUNT(*) FROM $table WHERE $condition", Int::class.java))
    private fun quantity(): Int = requireNotNull(jdbc.queryForObject("SELECT COALESCE(SUM(allocated_quantity), 0) FROM concert_purchase_quotas", Int::class.java))
}
