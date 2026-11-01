package me.park.openrush.reservation.support

import me.park.openrush.TestcontainersConfiguration
import me.park.openrush.reservation.application.port.input.CreateReservationCommand
import me.park.openrush.reservation.application.port.input.CreateReservationResult
import me.park.openrush.reservation.application.port.input.CreateReservationUseCase
import me.park.openrush.reservation.application.port.output.AllocatePurchaseQuotaPort
import me.park.openrush.reservation.application.port.output.HoldPerformanceSeatsPort
import me.park.openrush.reservation.application.port.output.LoadPerformancePort
import me.park.openrush.reservation.application.port.output.LoadPerformanceSeatsPort
import me.park.openrush.reservation.application.port.output.SaveReservationPort
import me.park.openrush.reservation.domain.exception.ReservationRuleViolation
import org.junit.jupiter.api.BeforeEach
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.transaction.PlatformTransactionManager
import org.testcontainers.postgresql.PostgreSQLContainer
import kotlin.test.assertEquals
import kotlin.test.assertIs

@Import(TestcontainersConfiguration::class)
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.NONE,
    properties = ["spring.datasource.hikari.connection-init-sql=SET lock_timeout = '5s'"],
)
abstract class ReservationConcurrencyTestSupport {
    protected fun command(user: Long, seats: List<Long>) = CreateReservationCommand(user, performanceId, seats)
    protected fun id(sql: String): Long = checkNotNull(jdbc.queryForObject(sql, Long::class.java))
    protected fun count(sql: String): Int = checkNotNull(jdbc.queryForObject(sql, Int::class.java))
    protected fun race(commands: List<CreateReservationCommand>): List<Result<CreateReservationResult>> =
        ConcurrentReservationRequests(reservations).run(commands)

    protected fun assertOutcomes(results: List<Result<CreateReservationResult>>, successes: Int) {
        assertEquals(successes, results.count { it.isSuccess })
        results.filter { it.isFailure }.forEach { assertIs<ReservationRuleViolation>(it.exceptionOrNull()) }
    }

    protected fun assertConsistent(reservations: Int, heldSeats: Int) {
        assertEquals(reservations, count("select count(*) from reservations"))
        assertEquals(heldSeats, count("select count(*) from performance_seats where status = 'HELD'"))
        assertEquals(heldSeats, count("select count(*) from reservation_items"))
        assertEquals(heldSeats, count("select coalesce(sum(allocated_quantity), 0) from concert_purchase_quotas"))
        assertEquals(0, count("""
            select count(*) from concert_purchase_quotas q join concerts c on c.id = q.concert_id
            where q.allocated_quantity > c.max_tickets_per_user or q.allocated_quantity <> (
                select count(*) from reservation_items i join reservations r on r.id = i.reservation_id
                join performances p on p.id = r.performance_id where r.user_id = q.user_id and p.concert_id = q.concert_id
            )
        """.trimIndent()))
        assertEquals(0, count("""
            select count(*) from reservation_items i join performance_seats s on s.id = i.performance_seat_id
            join reservations r on r.id = i.reservation_id
            where s.reservation_id <> r.id or s.reservation_id is null or s.status <> 'HELD' or s.performance_id <> r.performance_id
        """.trimIndent()))
    }
    @BeforeEach
    fun prepareReservationData() {
        TestDatabaseCleaner(jdbc, postgres).clear()
        fixture = ReservationTestFixture(jdbc).apply { create() }
    }

    protected val performanceId: Long get() = fixture.performanceId
    protected val otherPerformanceId: Long get() = fixture.otherPerformanceId
    protected val seatIds: List<Long> get() = fixture.seatIds
    protected val otherSeatIds: List<Long> get() = fixture.otherSeatIds

    private lateinit var fixture: ReservationTestFixture
    @Autowired protected lateinit var reservations: CreateReservationUseCase
    @Autowired protected lateinit var seatsPort: LoadPerformanceSeatsPort
    @Autowired protected lateinit var holdSeatsPort: HoldPerformanceSeatsPort
    @Autowired protected lateinit var saveReservationPort: SaveReservationPort
    @Autowired protected lateinit var quotaPort: AllocatePurchaseQuotaPort
    @Autowired protected lateinit var performancePort: LoadPerformancePort
    @Autowired protected lateinit var jdbc: JdbcTemplate
    @Autowired protected lateinit var transactionManager: PlatformTransactionManager
    @Autowired private lateinit var postgres: PostgreSQLContainer
}
