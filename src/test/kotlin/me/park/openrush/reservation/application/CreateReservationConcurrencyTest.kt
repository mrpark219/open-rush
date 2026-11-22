package me.park.openrush.reservation.application

import jakarta.persistence.EntityManager
import me.park.openrush.support.database.DatabaseIntegrationTest
import me.park.openrush.support.database.prepareConcurrentConnections
import me.park.openrush.support.fixture.ConcertFixture
import me.park.openrush.support.fixture.InventoryFixture
import me.park.openrush.quota.adapter.output.persistence.repository.PurchaseQuotaRepository
import me.park.openrush.reservation.application.provided.CreateReservationCommand
import me.park.openrush.reservation.application.provided.CreateReservationResult
import me.park.openrush.reservation.application.provided.CreateReservationUseCase
import me.park.openrush.quota.application.provided.AllocatePurchaseQuotaUseCase
import me.park.openrush.quota.application.provided.AllocateQuotaCommand
import me.park.openrush.inventory.application.provided.HoldSeatsUseCase
import me.park.openrush.inventory.application.provided.HoldSeatsCommand
import me.park.openrush.inventory.application.provided.GetSeatsUseCase
import me.park.openrush.quota.domain.ConcertPurchaseQuota
import me.park.openrush.inventory.domain.PerformanceSeat
import me.park.openrush.inventory.domain.SeatStatus
import me.park.openrush.reservation.domain.exception.ReservationRuleViolationException
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.springframework.context.annotation.Primary
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate
import java.time.Instant
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotSame
import kotlin.test.assertTrue

@Import(CreateReservationConcurrencyTest.Configuration::class)
@DatabaseIntegrationTest(properties = ["spring.datasource.hikari.maximum-pool-size=16"])
class CreateReservationConcurrencyTest {
    @Autowired private lateinit var createReservationUseCase: CreateReservationUseCase
    @Autowired private lateinit var jdbcTemplate: JdbcTemplate
    @Autowired private lateinit var coordinatedSeatLoader: CoordinatedSeatLoader
    @Autowired private lateinit var entityManager: EntityManager
    @Autowired private lateinit var transactionManager: PlatformTransactionManager
    @Autowired private lateinit var allocatePurchaseQuotaUseCase: AllocatePurchaseQuotaUseCase
    @Autowired private lateinit var holdSeatsUseCase: HoldSeatsUseCase
    @Autowired private lateinit var purchaseQuotaRepository: PurchaseQuotaRepository

    private var concertId = 0L
    private var performanceId = 0L
    private lateinit var seatIds: List<Long>

    @BeforeEach
    fun setUp() {
        coordinatedSeatLoader.afterRead.set(null)

        concertId = ConcertFixture(jdbcTemplate).createConcert()
        performanceId = newPerformance()
        seatIds = (1..8).map { number ->
            InventoryFixture(jdbcTemplate).createSeat(performanceId, "A$number", amount = 50_000)
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
            jdbcTemplate.queryForObject("SELECT reservation_id FROM performance_seats WHERE id = ?", Long::class.java, seatIds.first()))
    }

    @Test
    fun `기존 한도 행을 공유하는 동시 요청은 남은 네 매를 정확히 확보한다`() {
        jdbcTemplate.update("INSERT INTO concert_purchase_quotas(user_id, concert_id, allocated_quantity) VALUES (1, ?, 0)", concertId)
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
            InventoryFixture(jdbcTemplate).createSeat(otherPerformance, "B$it", amount = 50_000)
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
        coordinatedSeatLoader.afterRead.set {
            coordinatedSeatLoader.afterRead.set(null)
            createReservationUseCase.create(command(2, seatIds[1]))
        }

        assertFailsWith<ReservationRuleViolationException> {
            createReservationUseCase.create(CreateReservationCommand(1, performanceId, seatIds.take(2)))
        }

        assertEquals("AVAILABLE", jdbcTemplate.queryForObject("SELECT status FROM performance_seats WHERE id = ?", String::class.java, seatIds[0]))
        assertEquals(1, held())
        assertEquals(1, allocated())
        assertEquals(1, count("reservations"))
        assertEquals(1, count("reservation_items"))
        assertEquals(0, jdbcTemplate.queryForObject("SELECT count(*) FROM concert_purchase_quotas WHERE user_id = 1", Int::class.java))
    }

    @Test
    fun `한도 초과 시 기존 예약을 유지하고 추가 예약과 좌석 변경은 남지 않는다`() {
        createReservationUseCase.create(CreateReservationCommand(1, performanceId, seatIds.take(3)))
        assertFailsWith<ReservationRuleViolationException> {
            createReservationUseCase.create(CreateReservationCommand(1, performanceId, seatIds.drop(3).take(2)))
        }

        assertEquals(3, allocated())
        assertEquals(3, held())
        assertEquals(1, count("reservations"))
        assertEquals(3, count("reservation_items"))
    }

    @Test
    fun `벌크 갱신 후 재조회는 최신 상태를 반환하고 수정 시각도 갱신된다`() {
        val reservation = createReservationUseCase.create(command(1, seatIds.first()))
        jdbcTemplate.update("UPDATE performance_seats SET modified_at = '2000-01-01T00:00:00Z' WHERE id = ?", seatIds[1])
        jdbcTemplate.update("UPDATE concert_purchase_quotas SET modified_at = '2000-01-01T00:00:00Z'")
        TransactionTemplate(transactionManager).executeWithoutResult {
            val quotaId = jdbcTemplate.queryForObject("SELECT id FROM concert_purchase_quotas", Long::class.java)!!
            val oldSeat = entityManager.find(PerformanceSeat::class.java, seatIds[1])
            allocatePurchaseQuotaUseCase.allocate(AllocateQuotaCommand(1, concertId, 1))
            holdSeatsUseCase.hold(HoldSeatsCommand(reservation.reservationId, performanceId, listOf(seatIds[1])))

            val quota = entityManager.find(ConcertPurchaseQuota::class.java, quotaId)
            val seat = entityManager.find(PerformanceSeat::class.java, seatIds[1])
            assertNotSame(oldSeat, seat)
            assertEquals(2, quota.allocatedQuantity)
            assertEquals(SeatStatus.HELD, seat.status)
            assertEquals(reservation.reservationId, seat.reservationId)
            assertTrue(quota.modifiedAt!! > Instant.parse("2000-01-01T00:00:00Z"))
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
        val reservation = createReservationUseCase.create(command(1, seatIds.first()))
        val otherPerformance = newPerformance()
        val otherSeat = InventoryFixture(jdbcTemplate).createSeat(otherPerformance, "B1", amount = 50_000)

        assertFailsWith<me.park.openrush.inventory.domain.exception.InventoryRuleViolationException> {
            TransactionTemplate(transactionManager).executeWithoutResult {
                holdSeatsUseCase.hold(HoldSeatsCommand(reservation.reservationId, performanceId, listOf(seatIds[1], otherSeat)))
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
        checkNotNull(jdbcTemplate.dataSource).prepareConcurrentConnections(commands.size)
        val barrier = CyclicBarrier(commands.size) { coordinatedSeatLoader.afterRead.set(null) }
        // 모든 요청이 AVAILABLE 상태를 읽은 뒤 갱신을 시작시켜 실제 쓰기 경합을 만든다.
        coordinatedSeatLoader.afterRead.set { barrier.await(10, TimeUnit.SECONDS) }
        val executor = Executors.newFixedThreadPool(commands.size)
        return try {
            val futures = commands.map { command -> executor.submit<Result<CreateReservationResult>> {
                runCatching { createReservationUseCase.create(command) }
            } }
            futures.map { it.get(30, TimeUnit.SECONDS) }
        } finally {
            coordinatedSeatLoader.afterRead.set(null)
            executor.shutdownNow()
            assertTrue(executor.awaitTermination(10, TimeUnit.SECONDS))
        }
    }

    private fun assertPolicyFailures(results: List<Result<CreateReservationResult>>) {
        results.filter { it.isFailure }.forEach {
            assertTrue(it.exceptionOrNull() is ReservationRuleViolationException, "예상하지 않은 실패: ${it.exceptionOrNull()}")
        }
    }

    private fun command(userId: Long, seatId: Long) = CreateReservationCommand(userId, performanceId, listOf(seatId))
    private fun count(table: String) = jdbcTemplate.queryForObject("SELECT count(*) FROM $table", Int::class.java)!!
    private fun allocated() = jdbcTemplate.queryForObject("SELECT coalesce(sum(allocated_quantity), 0) FROM concert_purchase_quotas", Int::class.java)!!
    private fun held() = jdbcTemplate.queryForObject("SELECT count(*) FROM performance_seats WHERE status = 'HELD'", Int::class.java)!!
    private fun newPerformance() = ConcertFixture(jdbcTemplate).createPerformance(concertId)

    @TestConfiguration(proxyBeanMethods = false)
    class Configuration {
        @Bean
        @Primary
        fun coordinatedSeatLoader(inventoryService: me.park.openrush.inventory.application.InventoryService) = CoordinatedSeatLoader(inventoryService)
    }

    class CoordinatedSeatLoader(private val delegate: me.park.openrush.inventory.application.InventoryService) : GetSeatsUseCase {
        val afterRead = AtomicReference<(() -> Unit)?>(null)
        override fun findSeats(performanceId: Long, seatIds: List<Long>): List<me.park.openrush.inventory.application.provided.SeatDetails> {
            val seatDetails = delegate.findSeats(performanceId, seatIds)
            afterRead.get()?.invoke()
            return seatDetails
        }
    }
}
