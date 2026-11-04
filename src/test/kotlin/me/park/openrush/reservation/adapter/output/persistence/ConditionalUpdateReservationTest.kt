package me.park.openrush.reservation.adapter.output.persistence

import jakarta.persistence.EntityManager
import me.park.openrush.TestcontainersConfiguration
import me.park.openrush.reservation.adapter.output.persistence.repository.PurchaseQuotaRepository
import me.park.openrush.reservation.application.port.input.CreateReservationCommand
import me.park.openrush.reservation.application.port.input.CreateReservationResult
import me.park.openrush.reservation.application.port.input.CreateReservationUseCase
import me.park.openrush.reservation.application.port.output.AllocatePurchaseQuotaPort
import me.park.openrush.reservation.application.port.output.AllocateQuotaCommand
import me.park.openrush.reservation.application.port.output.HoldPerformanceSeatsPort
import me.park.openrush.reservation.application.port.output.HoldSeatsCommand
import me.park.openrush.reservation.application.port.output.LoadPerformanceSeatsPort
import me.park.openrush.reservation.domain.ConcertPurchaseQuota
import me.park.openrush.reservation.domain.seat.PerformanceSeat
import me.park.openrush.reservation.domain.seat.SeatStatus
import me.park.openrush.reservation.domain.exception.ReservationRuleViolation
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.springframework.context.annotation.Primary
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotSame
import kotlin.test.assertTrue

@Import(TestcontainersConfiguration::class, ConditionalUpdateReservationTest.Configuration::class)
@SpringBootTest(properties = ["spring.datasource.hikari.maximum-pool-size=16"])
class ConditionalUpdateReservationTest {
    @Autowired private lateinit var useCase: CreateReservationUseCase
    @Autowired private lateinit var jdbc: JdbcTemplate
    @Autowired private lateinit var loader: CoordinatedSeatLoader
    @Autowired private lateinit var entityManager: EntityManager
    @Autowired private lateinit var transactionManager: PlatformTransactionManager
    @Autowired private lateinit var quotaPort: AllocatePurchaseQuotaPort
    @Autowired private lateinit var seatPort: HoldPerformanceSeatsPort
    @Autowired private lateinit var purchaseQuotaRepository: PurchaseQuotaRepository

    private var concertId = 0L
    private var performanceId = 0L
    private lateinit var seatIds: List<Long>

    @BeforeEach
    fun setUp() {
        loader.afterRead.set(null)
        jdbc.execute("TRUNCATE concerts CASCADE")
        concertId = id("INSERT INTO concerts(title, description, max_tickets_per_user) VALUES ('공연', '', 4) RETURNING id")
        performanceId = newPerformance()
        seatIds = (1..8).map { number ->
            id("""INSERT INTO performance_seats(performance_id, seat_number, price, status)
                VALUES (?, ?, 50000, 'AVAILABLE') RETURNING id""", performanceId, "A$number")
        }
    }

    @Test
    fun `같은 좌석을 동시에 요청하면 한 명만 선점하고 실패한 사용자의 한도와 예약은 남지 않는다`() {
        val results = race((1L..8L).map { command(it, seatIds.first()) })

        assertEquals(1, results.count { it.isSuccess })
        assertPolicyFailures(results)
        assertEquals(1, count("reservations"))
        assertEquals(1, count("reservation_items"))
        assertEquals(1, count("concert_purchase_quotas"))
        assertEquals(1, allocated())
        assertEquals(1, held())
        assertEquals(results.single { it.isSuccess }.getOrThrow().reservationId,
            jdbc.queryForObject("SELECT reservation_id FROM performance_seats WHERE id = ?", Long::class.java, seatIds.first()))
    }

    @Test
    fun `기존 한도 행을 공유하는 동시 요청은 남은 네 매를 정확히 확보한다`() {
        jdbc.update("INSERT INTO concert_purchase_quotas(user_id, concert_id, allocated_quantity) VALUES (1, ?, 0)", concertId)
        assertQuotaRace()
    }

    @Test
    fun `한도 행이 없는 상태의 동시 요청도 한 행만 생성하고 네 매를 확보한다`() {
        assertQuotaRace()
    }

    @Test
    fun `다른 회차의 동시 요청도 같은 공연의 구매 한도를 공유한다`() {
        val otherPerformance = newPerformance()
        val otherSeats = (1..4).map {
            id("""INSERT INTO performance_seats(performance_id, seat_number, price, status)
                VALUES (?, ?, 50000, 'AVAILABLE') RETURNING id""", otherPerformance, "B$it")
        }
        val commands = seatIds.take(4).map { command(1, it) } +
            otherSeats.map { CreateReservationCommand(1, otherPerformance, listOf(it)) }
        val results = race(commands)

        assertEquals(4, results.count { it.isSuccess })
        assertPolicyFailures(results)
        assertEquals(4, allocated())
        assertEquals(4, held())
        assertEquals(4, count("reservations"))
    }

    @Test
    fun `조회 뒤 일부 좌석이 선점되면 부분 UPDATE와 예약과 한도를 모두 롤백한다`() {
        // 외부 요청이 AVAILABLE 상태를 읽은 직후 다른 트랜잭션이 두 번째 좌석을 선점한다.
        loader.afterRead.set {
            loader.afterRead.set(null)
            useCase.create(command(2, seatIds[1]))
        }

        assertFailsWith<ReservationRuleViolation> {
            useCase.create(CreateReservationCommand(1, performanceId, seatIds.take(2)))
        }

        assertEquals("AVAILABLE", jdbc.queryForObject("SELECT status FROM performance_seats WHERE id = ?", String::class.java, seatIds[0]))
        assertEquals(1, held())
        assertEquals(1, allocated())
        assertEquals(1, count("reservations"))
        assertEquals(1, count("reservation_items"))
        assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM concert_purchase_quotas WHERE user_id = 1", Int::class.java))
    }

    @Test
    fun `한도 초과 시 기존 예약을 유지하고 추가 예약과 좌석 변경은 남지 않는다`() {
        useCase.create(CreateReservationCommand(1, performanceId, seatIds.take(3)))
        assertFailsWith<ReservationRuleViolation> {
            useCase.create(CreateReservationCommand(1, performanceId, seatIds.drop(3).take(2)))
        }

        assertEquals(3, allocated())
        assertEquals(3, held())
        assertEquals(1, count("reservations"))
        assertEquals(3, count("reservation_items"))
    }

    @Test
    fun `벌크 갱신 후 재조회는 최신 상태를 반환하고 수정 시각도 갱신된다`() {
        val reservation = useCase.create(command(1, seatIds.first()))
        jdbc.update("UPDATE performance_seats SET modified_at = '2000-01-01T00:00:00Z' WHERE id = ?", seatIds[1])
        jdbc.update("UPDATE concert_purchase_quotas SET modified_at = '2000-01-01T00:00:00Z'")
        TransactionTemplate(transactionManager).executeWithoutResult {
            val quotaId = jdbc.queryForObject("SELECT id FROM concert_purchase_quotas", Long::class.java)!!
            val oldSeat = entityManager.find(PerformanceSeat::class.java, seatIds[1])
            quotaPort.allocate(AllocateQuotaCommand(1, concertId, 1))
            seatPort.hold(HoldSeatsCommand(reservation.reservationId, performanceId, listOf(seatIds[1])))

            val quota = entityManager.find(ConcertPurchaseQuota::class.java, quotaId)
            val seat = entityManager.find(PerformanceSeat::class.java, seatIds[1])
            assertNotSame(oldSeat, seat)
            assertEquals(2, quota.allocatedQuantity)
            assertEquals(SeatStatus.HELD, seat.status)
            assertEquals(reservation.reservationId, seat.reservationId)
            assertTrue(quota.modifiedAt!! > java.time.Instant.parse("2000-01-01T00:00:00Z"))
            assertTrue(seat.modifiedAt!! > oldSeat.modifiedAt!!)
        }
    }

    @Test
    fun `한도 행 중복 생성은 명시적 flush 없이 Repository 호출에서 즉시 실패한다`() {
        TransactionTemplate(transactionManager).executeWithoutResult {
            purchaseQuotaRepository.createQuota(1, concertId)
        }

        assertFailsWith<DataIntegrityViolationException> {
            TransactionTemplate(transactionManager).executeWithoutResult {
                purchaseQuotaRepository.createQuota(1, concertId)
                error("중복 INSERT는 커밋 전 Repository 호출에서 실패해야 합니다.")
            }
        }
        assertEquals(1, count("concert_purchase_quotas"))
        assertEquals(0, allocated())
    }

    @Test
    fun `다른 회차의 좌석을 선점하려 하면 같은 요청의 유효 좌석도 롤백한다`() {
        val reservation = useCase.create(command(1, seatIds.first()))
        val otherPerformance = newPerformance()
        val otherSeat = id("""INSERT INTO performance_seats(performance_id, seat_number, price, status)
            VALUES (?, 'B1', 50000, 'AVAILABLE') RETURNING id""", otherPerformance)

        assertFailsWith<ReservationRuleViolation> {
            TransactionTemplate(transactionManager).executeWithoutResult {
                seatPort.hold(HoldSeatsCommand(reservation.reservationId, performanceId, listOf(seatIds[1], otherSeat)))
            }
        }
        assertEquals(1, held())
    }

    private fun assertQuotaRace() {
        val results = race(seatIds.map { command(1, it) })
        assertEquals(4, results.count { it.isSuccess })
        assertPolicyFailures(results)
        assertEquals(1, count("concert_purchase_quotas"))
        assertEquals(4, allocated())
        assertEquals(4, held())
        assertEquals(4, count("reservations"))
        assertEquals(4, count("reservation_items"))
    }

    private fun race(commands: List<CreateReservationCommand>): List<Result<CreateReservationResult>> {
        val barrier = CyclicBarrier(commands.size) { loader.afterRead.set(null) }
        // 모든 요청이 AVAILABLE 상태를 읽은 뒤 갱신을 시작시켜 실제 쓰기 경합을 만든다.
        loader.afterRead.set { barrier.await(10, TimeUnit.SECONDS) }
        val executor = Executors.newFixedThreadPool(commands.size)
        return try {
            val futures = commands.map { command -> executor.submit<Result<CreateReservationResult>> {
                runCatching { useCase.create(command) }
            } }
            futures.map { it.get(30, TimeUnit.SECONDS) }
        } finally {
            loader.afterRead.set(null)
            executor.shutdownNow()
            assertTrue(executor.awaitTermination(10, TimeUnit.SECONDS))
        }
    }

    private fun assertPolicyFailures(results: List<Result<CreateReservationResult>>) {
        results.filter { it.isFailure }.forEach {
            assertTrue(it.exceptionOrNull() is ReservationRuleViolation, "예상하지 않은 실패: ${it.exceptionOrNull()}")
        }
    }

    private fun command(userId: Long, seatId: Long) = CreateReservationCommand(userId, performanceId, listOf(seatId))
    private fun count(table: String) = jdbc.queryForObject("SELECT count(*) FROM $table", Int::class.java)!!
    private fun allocated() = jdbc.queryForObject("SELECT coalesce(sum(allocated_quantity), 0) FROM concert_purchase_quotas", Int::class.java)!!
    private fun held() = jdbc.queryForObject("SELECT count(*) FROM performance_seats WHERE status = 'HELD'", Int::class.java)!!
    private fun id(sql: String, vararg args: Any) = jdbc.queryForObject(sql, Long::class.java, *args)!!
    private fun newPerformance() = id("""INSERT INTO performances(concert_id, venue, starts_at, booking_opens_at, booking_closes_at)
        VALUES (?, '공연장', CURRENT_TIMESTAMP + INTERVAL '2 days', CURRENT_TIMESTAMP - INTERVAL '1 day',
        CURRENT_TIMESTAMP + INTERVAL '1 day') RETURNING id""", concertId)

    @TestConfiguration(proxyBeanMethods = false)
    class Configuration {
        @Bean
        @Primary
        fun coordinatedSeatLoader(adapter: ReservationPersistenceAdapter) = CoordinatedSeatLoader(adapter)
    }

    class CoordinatedSeatLoader(private val delegate: ReservationPersistenceAdapter) : LoadPerformanceSeatsPort {
        val afterRead = AtomicReference<(() -> Unit)?>(null)
        override fun findSeats(performanceId: Long, seatIds: List<Long>): List<PerformanceSeat> {
            val seats = delegate.findSeats(performanceId, seatIds)
            afterRead.get()?.invoke()
            return seats
        }
    }
}
