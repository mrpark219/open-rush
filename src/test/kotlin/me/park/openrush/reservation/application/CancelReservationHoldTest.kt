package me.park.openrush.reservation.application

import com.zaxxer.hikari.HikariDataSource
import me.park.openrush.reservation.adapter.output.persistence.ReservationPersistenceAdapter
import me.park.openrush.support.database.DatabaseIntegrationTest
import me.park.openrush.support.database.prepareConcurrentConnections
import me.park.openrush.support.fixture.ConcertFixture
import me.park.openrush.support.fixture.InventoryFixture
import me.park.openrush.reservation.application.provided.CancelReservationHoldCommand
import me.park.openrush.reservation.application.provided.CancelReservationHoldUseCase
import me.park.openrush.reservation.application.provided.CreateReservationCommand
import me.park.openrush.reservation.application.provided.CreateReservationUseCase
import me.park.openrush.reservation.application.required.ReservationRepositoryPort
import me.park.openrush.reservation.domain.Reservation
import me.park.openrush.reservation.domain.exception.ReservationRuleViolationException
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
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
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

@Import(CancelReservationHoldTest.Configuration::class)
@DatabaseIntegrationTest(properties = ["spring.datasource.hikari.maximum-pool-size=16"])
class CancelReservationHoldTest {
    @Autowired
    private lateinit var createReservationUseCase: CreateReservationUseCase
    @Autowired
    private lateinit var cancelReservationHoldUseCase: CancelReservationHoldUseCase
    @Autowired
    private lateinit var jdbcTemplate: JdbcTemplate
    @Autowired
    private lateinit var coordinatedReservationLoader: CoordinatedReservationLoader
    @Autowired
    private lateinit var transactionManager: PlatformTransactionManager
    private var performanceId = 0L
    private lateinit var seatIds: List<Long>
    private val now = Instant.parse("2026-11-04T00:00:00Z")

    @BeforeEach
    fun setUp() {
        coordinatedReservationLoader.afterRead.set(null)

        val concertId = ConcertFixture(jdbcTemplate).createConcert()
        performanceId = ConcertFixture(jdbcTemplate).createPerformance(
            concertId = concertId,
            startsAt = Instant.parse("2026-11-06T00:00:00Z"),
            bookingOpensAt = Instant.parse("2026-11-03T00:00:00Z"),
            bookingClosesAt = Instant.parse("2026-11-05T00:00:00Z"),
        )
        seatIds = (1..4).map {
            InventoryFixture(jdbcTemplate).createSeat(performanceId, "A$it", amount = 50_000)
        }
    }

    @Test
    fun `예매 전체를 취소하면 좌석과 소유 정보를 해제하고 해당 수량만 한도를 반환한다`() {
        val first = reserve(seatIds.take(2))
        val second = reserve(seatIds.drop(2).take(1))
        jdbcTemplate.update("UPDATE reservations SET modified_at = '2000-01-01T00:00:00Z'")
        jdbcTemplate.update("UPDATE performance_seats SET modified_at = '2000-01-01T00:00:00Z'")
        jdbcTemplate.update("UPDATE concert_purchase_quotas SET modified_at = '2000-01-01T00:00:00Z'")

        cancelReservationHoldUseCase.cancelHold(CancelReservationHoldCommand(1, first))

        assertEquals("CANCELLED", status(first))
        assertEquals("HELD", status(second))
        assertEquals(1, allocated())
        assertEquals(
            2,
            count(
                "SELECT count(*) FROM performance_seats WHERE status = 'AVAILABLE' AND reservation_id IS NULL AND id IN (?, ?)",
                seatIds[0],
                seatIds[1]
            )
        )
        assertEquals(
            1,
            count("SELECT count(*) FROM performance_seats WHERE status = 'HELD' AND reservation_id = ?", second)
        )
        assertEquals(2, count("SELECT count(*) FROM reservation_items WHERE reservation_id = ?", first))
        assertEquals(1, count("SELECT count(*) FROM reservations WHERE id = ? AND modified_at > '2000-01-01'", first))
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
    fun `타인의 예매와 존재하지 않는 예매는 취소할 수 없다`() {
        val reservationId = reserve(seatIds.take(1))
        assertFailsWith<ReservationRuleViolationException> {
            cancelReservationHoldUseCase.cancelHold(
                CancelReservationHoldCommand(
                    2,
                    reservationId
                )
            )
        }
        assertFailsWith<ReservationRuleViolationException> {
            cancelReservationHoldUseCase.cancelHold(
                CancelReservationHoldCommand(
                    1,
                    Long.MAX_VALUE
                )
            )
        }
        assertHeld(reservationId, 1)
    }

    @Test
    fun `만료 경계와 선점 시각 이전에는 취소할 수 없다`() {
        val reservationId = reserve(seatIds.take(1))
        jdbcTemplate.update(
            "UPDATE reservations SET held_at = ?, expires_at = ? WHERE id = ?",
            java.sql.Timestamp.from(now.minusSeconds(300)), java.sql.Timestamp.from(now), reservationId
        )
        assertFailsWith<ReservationRuleViolationException> {
            cancelReservationHoldUseCase.cancelHold(
                CancelReservationHoldCommand(
                    1,
                    reservationId
                )
            )
        }
        assertHeld(reservationId, 1)
        jdbcTemplate.update(
            "UPDATE reservations SET held_at = ?, expires_at = ? WHERE id = ?",
            java.sql.Timestamp.from(now.plusSeconds(1)), java.sql.Timestamp.from(now.plusSeconds(300)), reservationId
        )
        assertFailsWith<ReservationRuleViolationException> {
            cancelReservationHoldUseCase.cancelHold(
                CancelReservationHoldCommand(
                    1,
                    reservationId
                )
            )
        }
        assertHeld(reservationId, 1)
    }

    @Test
    fun `확정 취소 만료 상태에서는 자원을 반환하지 않는다`() {
        val reservationId = reserve(seatIds.take(1))
        listOf("CONFIRMED", "CANCELLED", "EXPIRED").forEach { status ->
            jdbcTemplate.update(
                "UPDATE reservations SET status = ?, confirmed_at = ? WHERE id = ?",
                status, if (status == "CONFIRMED") java.sql.Timestamp.from(now) else null, reservationId
            )
            assertFailsWith<ReservationRuleViolationException> {
                cancelReservationHoldUseCase.cancelHold(
                    CancelReservationHoldCommand(
                        1,
                        reservationId
                    )
                )
            }
            assertEquals(status, status(reservationId))
            assertEquals(1, allocated())
            assertEquals(1, count("SELECT count(*) FROM performance_seats WHERE status = 'HELD'"))
        }
    }

    @Test
    fun `동시에 취소한 요청 중 하나만 성공하고 한도는 한 번만 반환한다`() {
        val reservationId = reserve(seatIds.take(2))
        reserve(seatIds.drop(2).take(1))
        val results = race(List(8) { reservationId })
        assertEquals(1, results.count { it.isSuccess })
        results.filter { it.isFailure }.forEach { assertTrue(it.exceptionOrNull() is ReservationRuleViolationException) }
        assertEquals("CANCELLED", status(reservationId))
        assertEquals(1, allocated())
        assertEquals(1, count("SELECT count(*) FROM performance_seats WHERE status = 'HELD'"))
    }

    @Test
    fun `취소 후 재선점한 좌석은 이전 예매의 중복 취소로 해제되지 않는다`() {
        val old = reserve(seatIds.take(1))
        cancelReservationHoldUseCase.cancelHold(CancelReservationHoldCommand(1, old))
        val replacement = reserve(seatIds.take(1))
        assertFailsWith<ReservationRuleViolationException> { cancelReservationHoldUseCase.cancelHold(CancelReservationHoldCommand(1, old)) }
        assertHeld(replacement, 1)
        assertEquals(
            replacement,
            jdbcTemplate.queryForObject(
                "SELECT reservation_id FROM performance_seats WHERE id = ?",
                Long::class.java,
                seatIds.first()
            )
        )
    }

    @Test
    fun `조회 후 예매가 확정되면 조건부 상태 전환이 실패하고 자원은 반환되지 않는다`() {
        val reservationId = reserve(seatIds.take(1))
        coordinatedReservationLoader.afterRead.set {
            TransactionTemplate(transactionManager).apply {
                propagationBehavior = TransactionDefinition.PROPAGATION_REQUIRES_NEW
            }.executeWithoutResult {
                jdbcTemplate.update(
                    "UPDATE reservations SET status = 'CONFIRMED', confirmed_at = ? WHERE id = ?",
                    java.sql.Timestamp.from(now), reservationId
                )
            }
        }
        assertFailsWith<ReservationRuleViolationException> {
            cancelReservationHoldUseCase.cancelHold(
                CancelReservationHoldCommand(
                    1,
                    reservationId
                )
            )
        }
        coordinatedReservationLoader.afterRead.set(null)
        assertEquals("CONFIRMED", status(reservationId))
        assertEquals(1, allocated())
        assertEquals(1, count("SELECT count(*) FROM performance_seats WHERE status = 'HELD'"))
    }

    @Test
    fun `일부 좌석이 다른 예매 소유라면 부분 해제와 상태 변경과 한도 반환을 모두 롤백한다`() {
        val reservationId = reserve(seatIds.take(2))
        val other = reserve(seatIds.drop(2).take(1))
        jdbcTemplate.update("UPDATE performance_seats SET reservation_id = ? WHERE id = ?", other, seatIds[1])
        assertFailsWith<IllegalStateException> {
            cancelReservationHoldUseCase.cancelHold(
                CancelReservationHoldCommand(
                    1,
                    reservationId
                )
            )
        }
        assertHeld(reservationId, 3)
        assertEquals(
            other,
            jdbcTemplate.queryForObject("SELECT reservation_id FROM performance_seats WHERE id = ?", Long::class.java, seatIds[1])
        )
    }

    @Test
    fun `한도가 부족하거나 행이 없으면 상태 전환도 롤백한다`() {
        val reservationId = reserve(seatIds.take(2))
        jdbcTemplate.update("UPDATE concert_purchase_quotas SET allocated_quantity = 1")
        assertFailsWith<IllegalStateException> {
            cancelReservationHoldUseCase.cancelHold(
                CancelReservationHoldCommand(
                    1,
                    reservationId
                )
            )
        }
        assertHeld(reservationId, 1)
        jdbcTemplate.update("DELETE FROM concert_purchase_quotas")
        assertFailsWith<IllegalStateException> {
            cancelReservationHoldUseCase.cancelHold(
                CancelReservationHoldCommand(
                    1,
                    reservationId
                )
            )
        }
        assertHeld(reservationId, 0)
    }

    @Test
    fun `같은 사용자의 서로 다른 예매를 동시에 취소해도 한도를 정확히 반환한다`() {
        val reservations = seatIds.map { reserve(listOf(it)) }
        val results = race(reservations)
        results.forEach { it.getOrThrow() }
        reservations.forEach { assertEquals("CANCELLED", status(it)) }
        assertEquals(0, allocated())
        assertEquals(
            4,
            count("SELECT count(*) FROM performance_seats WHERE status = 'AVAILABLE' AND reservation_id IS NULL")
        )
        reserve(seatIds)
        assertEquals(4, allocated())
    }

    private fun race(reservationIds: List<Long>): List<Result<Unit>> {
        checkNotNull(jdbcTemplate.dataSource).prepareConcurrentConnections(reservationIds.size)
        val barrier = CyclicBarrier(reservationIds.size)
        val timeoutDiagnostics = AtomicReference<String>()
        coordinatedReservationLoader.afterRead.set {
            try {
                barrier.await(10, TimeUnit.SECONDS)
            } catch (failure: TimeoutException) {
                timeoutDiagnostics.compareAndSet(null, cancellationDiagnostics())
                throw failure
            }
        }
        val threadNumber = AtomicInteger()
        val executor = Executors.newFixedThreadPool(reservationIds.size) { task ->
            Thread(task, "reservation-cancel-${threadNumber.incrementAndGet()}")
        }
        return try {
            reservationIds.map { reservationId ->
                executor.submit<Result<Unit>> {
                    runCatching {
                        cancelReservationHoldUseCase.cancelHold(
                            CancelReservationHoldCommand(
                                1,
                                reservationId
                            )
                        )
                    }
                }
            }.map { it.get(30, TimeUnit.SECONDS) }.also { results ->
                val unexpectedFailures = results.mapIndexedNotNull { index, result ->
                    result.exceptionOrNull()
                        ?.takeUnless { it is ReservationRuleViolationException }
                        ?.let { failure ->
                            AssertionError("예약 ${reservationIds[index]} 취소 실패", failure)
                        }
                }
                if (unexpectedFailures.isNotEmpty()) {
                    throw AssertionError(
                        "동시 취소 작업 ${unexpectedFailures.size}개가 예기치 않게 실패했습니다.\n" +
                            timeoutDiagnostics.get().orEmpty()
                    ).apply {
                        unexpectedFailures.forEach(::addSuppressed)
                    }
                }
            }
        } finally {
            coordinatedReservationLoader.afterRead.set(null)
            executor.shutdownNow()
            assertTrue(executor.awaitTermination(10, TimeUnit.SECONDS))
        }
    }

    private fun cancellationDiagnostics(): String {
        val poolState = runCatching {
            val connectionPool = jdbcTemplate.dataSource!!.unwrap(HikariDataSource::class.java).hikariPoolMXBean
            "DB connections: active=${connectionPool.activeConnections}, idle=${connectionPool.idleConnections}, " +
                "total=${connectionPool.totalConnections}, waiting=${connectionPool.threadsAwaitingConnection}"
        }.getOrElse { "DB connection diagnostics unavailable: $it" }
        val threadStacks = Thread.getAllStackTraces()
            .filterKeys { it.name.startsWith("reservation-cancel-") }
            .entries.sortedBy { it.key.name }
            .joinToString("\n") { (thread, stackTrace) ->
                "${thread.name} (${thread.state})\n" + stackTrace.joinToString("\n") { "    at $it" }
            }
        return "$poolState\n$threadStacks"
    }

    private fun reserve(selected: List<Long>) =
        createReservationUseCase.create(CreateReservationCommand(1, performanceId, selected)).reservationId

    private fun status(id: Long) =
        jdbcTemplate.queryForObject("SELECT status FROM reservations WHERE id = ?", String::class.java, id)

    private fun allocated() = count("SELECT coalesce(sum(allocated_quantity), 0) FROM concert_purchase_quotas")
    private fun assertHeld(id: Long, quota: Int) {
        assertEquals("HELD", status(id))
        assertEquals(quota, allocated())
        assertEquals(
            0,
            count(
                "SELECT count(*) FROM performance_seats WHERE status != 'HELD' AND id IN (SELECT performance_seat_id FROM reservation_items WHERE reservation_id = ?)",
                id
            )
        )
    }

    private fun count(sql: String, vararg args: Any) = jdbcTemplate.queryForObject(sql, Int::class.java, *args)!!

    @TestConfiguration(proxyBeanMethods = false)
    class Configuration {
        @Bean
        @Primary
        fun cancellationClock(): Clock = Clock.fixed(Instant.parse("2026-11-04T00:00:00Z"), ZoneOffset.UTC)

        @Bean
        @Primary
        fun coordinatedReservationLoader(reservationPersistenceAdapter: ReservationPersistenceAdapter) = CoordinatedReservationLoader(reservationPersistenceAdapter)
    }

    class CoordinatedReservationLoader(private val delegate: ReservationPersistenceAdapter) :
        ReservationRepositoryPort by delegate {
        val afterRead = AtomicReference<(() -> Unit)?>(null)
        override fun findReservation(reservationId: Long): Reservation? {
            val reservation = delegate.findReservation(reservationId)
            afterRead.get()?.invoke()
            return reservation
        }
    }
}
