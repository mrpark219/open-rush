package me.park.openrush.reservation.adapter.input.web

import me.park.openrush.TestcontainersConfiguration
import me.park.openrush.common.web.GlobalExceptionHandler
import me.park.openrush.reservation.application.ReservationQueue
import me.park.openrush.reservation.application.port.input.CreateReservationCommand
import me.park.openrush.reservation.application.port.input.CreateReservationResult
import me.park.openrush.reservation.application.port.input.CreateReservationUseCase
import me.park.openrush.reservation.application.port.input.ExpireReservationHoldUseCase
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.http.MediaType
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.test.web.servlet.setup.MockMvcBuilders
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

@Import(TestcontainersConfiguration::class)
@SpringBootTest(properties = ["reservation.expiration.enabled=false", "reservation.queue.enabled=true",
    "reservation.queue.admissions-per-second=1000000000"])
class CreateReservationIdempotencyTest {
    @Autowired private lateinit var controller: ReservationController
    @Autowired private lateinit var detailsController: ReservationDetailsController
    @Autowired private lateinit var queue: ReservationQueue
    @Autowired private lateinit var create: CreateReservationUseCase
    @Autowired private lateinit var expiration: ExpireReservationHoldUseCase
    @Autowired private lateinit var jdbc: JdbcTemplate
    private lateinit var mvc: MockMvc
    private var performanceId = 0L
    private lateinit var seats: List<Long>

    @BeforeEach
    fun setUp() {
        mvc = MockMvcBuilders.standaloneSetup(controller, detailsController).setControllerAdvice(GlobalExceptionHandler()).build()
        jdbc.execute("TRUNCATE concerts CASCADE")
        val concert = id("INSERT INTO concerts(title, description, max_tickets_per_user) VALUES ('공연', '', 4) RETURNING id")
        performanceId = id("""INSERT INTO performances(concert_id, venue, starts_at, booking_opens_at, booking_closes_at)
            VALUES (?, '공연장', now() + interval '2 days', now() - interval '1 day', now() + interval '1 day') RETURNING id""", concert)
        seats = (1..3).map { number ->
            id("""INSERT INTO performance_seats(performance_id, seat_number, price, status)
                VALUES (?, ?, 50000, 'AVAILABLE') RETURNING id""", performanceId, "A$number")
        }
    }

    @Test
    fun `생성 응답의 Location에서 본인 예매의 최신 상태를 조회한다`() {
        val response = mvc.perform(request("read", seats.take(2), permit()))
            .andExpect(status().isCreated).andReturn().response
        val location = checkNotNull(response.getHeader("Location"))

        mvc.perform(get(location).param("userId", "1"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.status").value("HELD"))
            .andExpect(jsonPath("$.totalAmount").value(100000))
            .andExpect(jsonPath("$.seatIds.length()").value(2))
        mvc.perform(get(location).param("userId", "2")).andExpect(status().isNotFound)
        mvc.perform(get("/reservations/${Long.MAX_VALUE}").param("userId", "1")).andExpect(status().isNotFound)
        mvc.perform(get(location).param("userId", "0")).andExpect(status().isBadRequest)

        mvc.perform(post("$location/cancel-hold").contentType(MediaType.APPLICATION_JSON).content("""{"userId":1}"""))
            .andExpect(status().isNoContent)
        mvc.perform(get(location).param("userId", "1"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.status").value("CANCELLED"))
    }

    @Test
    fun `선점 만료 처리 후 생성 응답의 Location에서 만료 상태를 조회한다`() {
        val response = mvc.perform(request("expire", seats.take(2), permit()))
            .andExpect(status().isCreated).andReturn().response
        val location = checkNotNull(response.getHeader("Location"))
        val reservationId = location.substringAfterLast('/').toLong()

        mvc.perform(get(location).param("userId", "1"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.status").value("HELD"))

        assertEquals(1, jdbc.update(
            """UPDATE reservations
                SET held_at = now() - interval '6 minutes', expires_at = now() - interval '1 minute'
                WHERE id = ?""",
            reservationId,
        ))
        assertTrue(expiration.expireHold(reservationId))

        mvc.perform(get(location).param("userId", "1"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.status").value("EXPIRED"))
    }

    @Test
    fun `응답을 놓친 요청은 허가가 소비되고 예매가 취소된 뒤에도 최초 응답을 복구한다`() {
        val first = mvc.perform(request("retry", seats.take(2), permit()))
            .andExpect(status().isCreated).andReturn().response
        val location = checkNotNull(first.getHeader("Location"))
        mvc.perform(post("$location/cancel-hold").contentType(MediaType.APPLICATION_JSON).content("""{"userId":1}"""))
            .andExpect(status().isNoContent)

        val retry = mvc.perform(request("retry", seats.take(2).reversed()))
            .andExpect(status().isCreated).andReturn().response
        assertEquals(location, retry.getHeader("Location"))
        assertEquals(first.contentAsString, retry.contentAsString)
        assertEquals(1, count("reservations"))
        assertEquals(1, count("reservation_creation_records"))
    }

    @Test
    fun `같은 키로 다른 좌석을 요청하면 기존 결과를 덮어쓰지 않는다`() {
        mvc.perform(request("conflict", listOf(seats[0]), permit())).andExpect(status().isCreated)
        mvc.perform(request("conflict", listOf(seats[1])))
            .andExpect(status().isConflict).andExpect(jsonPath("$.code").value("IDEMPOTENCY_KEY_CONFLICT"))
        assertEquals(1, count("reservations"))
        assertEquals(1, count("reservation_creation_records"))
    }

    @Test
    fun `실패한 생성은 키를 남기지 않고 신규 생성은 입장 허가를 요구한다`() {
        mvc.perform(request("rollback", listOf(seats[0])))
            .andExpect(status().isTooManyRequests).andExpect(jsonPath("$.code").value("QUEUE_ADMISSION_REQUIRED"))
        mvc.perform(request("rollback", listOf(Long.MAX_VALUE), permit())).andExpect(status().isConflict)
        assertEquals(0, count("reservation_creation_records"))
        mvc.perform(request("rollback", listOf(seats[0]), permit())).andExpect(status().isCreated)
        assertEquals(1, count("reservations"))
    }

    @Test
    fun `동시 재전달은 한 번만 선점하고 다른 사용자는 같은 키를 사용할 수 있다`() {
        val command = CreateReservationCommand(1, performanceId, seats.take(2), "concurrent")
        val barrier = CyclicBarrier(2)
        val executor = Executors.newFixedThreadPool(2)
        try {
            val results = List(2) {
                executor.submit<CreateReservationResult> {
                    barrier.await(5, TimeUnit.SECONDS)
                    create.create(command)
                }
            }.map { it.get(10, TimeUnit.SECONDS) }
            assertEquals(results[0], results[1])
            assertEquals(1, count("reservations"))
            assertEquals(2, jdbc.queryForObject("SELECT sum(allocated_quantity) FROM concert_purchase_quotas", Int::class.java))
            val other = create.create(command.copy(userId = 2, seatIds = seats.takeLast(1)))
            assertNotEquals(results[0].reservationId, other.reservationId)
            assertEquals(2, count("reservation_creation_records"))
        } finally {
            executor.shutdownNow()
            assertTrue(executor.awaitTermination(10, TimeUnit.SECONDS))
        }
    }

    private fun request(key: String, selected: List<Long>, ticket: String? = null) = post("/reservations")
        .contentType(MediaType.APPLICATION_JSON)
        .header("Idempotency-Key", key)
        .content("""{"userId":1,"performanceId":$performanceId,"seatIds":[${selected.joinToString(",")}]}""")
        .also { if (ticket != null) it.header("X-Queue-Ticket", ticket) }

    private fun permit() = queue.join(1).ticketId.toString()
    private fun count(table: String) = jdbc.queryForObject("SELECT count(*) FROM $table", Int::class.java)!!
    private fun id(sql: String, vararg args: Any) = jdbc.queryForObject(sql, Long::class.java, *args)!!
}
