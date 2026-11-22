package me.park.openrush.reservation.adapter.input.web

import me.park.openrush.support.database.DatabaseIntegrationTest
import me.park.openrush.support.fixture.ConcertFixture
import me.park.openrush.support.fixture.InventoryFixture
import me.park.openrush.support.database.awaitBlockedTransaction
import me.park.openrush.queue.application.provided.JoinQueueUseCase
import me.park.openrush.reservation.application.provided.CreateReservationCommand
import me.park.openrush.reservation.application.provided.CreateReservationResult
import me.park.openrush.reservation.application.provided.CreateReservationUseCase
import me.park.openrush.reservation.application.provided.ExpireReservationHoldUseCase
import me.park.openrush.support.web.GlobalExceptionHandler
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.http.MediaType
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.mock.web.MockHttpServletResponse
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.test.web.servlet.setup.MockMvcBuilders
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

@DatabaseIntegrationTest(
    properties = ["queue.enabled=true",
        "queue.admissions-per-second=1000000000"]
)
class CreateReservationIdempotencyTest {
    @Autowired
    private lateinit var reservationController: ReservationController
    @Autowired
    private lateinit var reservationDetailsController: ReservationDetailsController
    @Autowired
    private lateinit var joinQueueUseCase: JoinQueueUseCase
    @Autowired
    private lateinit var createReservationUseCase: CreateReservationUseCase
    @Autowired
    private lateinit var expireReservationHoldUseCase: ExpireReservationHoldUseCase
    @Autowired
    private lateinit var jdbcTemplate: JdbcTemplate
    @Autowired
    private lateinit var transactionManager: PlatformTransactionManager
    private lateinit var mockMvc: MockMvc
    private var performanceId = 0L
    private lateinit var seatIds: List<Long>

    @BeforeEach
    fun setUp() {
        mockMvc =
            MockMvcBuilders.standaloneSetup(reservationController, reservationDetailsController).setControllerAdvice(GlobalExceptionHandler())
                .build()

        val concertId = ConcertFixture(jdbcTemplate).createConcert()
        performanceId = ConcertFixture(jdbcTemplate).createPerformance(concertId)
        seatIds = (1..3).map { number ->
            InventoryFixture(jdbcTemplate).createSeat(performanceId, "A$number", amount = 50_000)
        }
    }

    @Test
    fun `생성 응답의 Location에서 본인 예매의 최신 상태를 조회한다`() {
        val response = mockMvc.perform(request("read", seatIds.take(2), permit()))
            .andExpect(status().isCreated).andReturn().response
        val location = checkNotNull(response.getHeader("Location"))

        mockMvc.perform(get(location).param("userId", "1"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.status").value("HELD"))
            .andExpect(jsonPath("$.totalAmount").value(100000))
            .andExpect(jsonPath("$.seatIds.length()").value(2))
        mockMvc.perform(get(location).param("userId", "2")).andExpect(status().isNotFound)
        mockMvc.perform(get("/reservations/${Long.MAX_VALUE}").param("userId", "1")).andExpect(status().isNotFound)
        mockMvc.perform(get(location).param("userId", "0")).andExpect(status().isBadRequest)

        mockMvc.perform(post("$location/cancel-hold").contentType(MediaType.APPLICATION_JSON).content("""{"userId":1}"""))
            .andExpect(status().isNoContent)
        mockMvc.perform(get(location).param("userId", "1"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.status").value("CANCELLED"))
    }

    @Test
    fun `선점 만료 처리 후 생성 응답의 Location에서 만료 상태를 조회한다`() {
        val response = mockMvc.perform(request("expire", seatIds.take(2), permit()))
            .andExpect(status().isCreated).andReturn().response
        val location = checkNotNull(response.getHeader("Location"))
        val reservationId = location.substringAfterLast('/').toLong()

        mockMvc.perform(get(location).param("userId", "1"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.status").value("HELD"))

        assertEquals(
            1, jdbcTemplate.update(
                """UPDATE reservations
                SET held_at = now() - interval '6 minutes', expires_at = now() - interval '1 minute'
                WHERE id = ?""",
                reservationId,
            )
        )
        assertTrue(expireReservationHoldUseCase.expireHold(reservationId))

        mockMvc.perform(get(location).param("userId", "1"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.status").value("EXPIRED"))
    }

    @Test
    fun `응답을 놓친 요청은 허가가 소비되고 예매가 취소된 뒤에도 최초 응답을 복구한다`() {
        val first = mockMvc.perform(request("retry", seatIds.take(2), permit()))
            .andExpect(status().isCreated).andReturn().response
        val location = checkNotNull(first.getHeader("Location"))
        mockMvc.perform(post("$location/cancel-hold").contentType(MediaType.APPLICATION_JSON).content("""{"userId":1}"""))
            .andExpect(status().isNoContent)

        val retry = mockMvc.perform(request("retry", seatIds.take(2).reversed()))
            .andExpect(status().isCreated).andReturn().response
        assertEquals(location, retry.getHeader("Location"))
        assertEquals(first.contentAsString, retry.contentAsString)
        assertEquals(1, count("reservations"))
        assertEquals(1, count("reservation_creation_records"))
    }

    @Test
    fun `같은 키로 다른 좌석을 요청하면 기존 결과를 덮어쓰지 않는다`() {
        mockMvc.perform(request("conflict", listOf(seatIds[0]), permit())).andExpect(status().isCreated)
        mockMvc.perform(request("conflict", listOf(seatIds[1])))
            .andExpect(status().isConflict).andExpect(jsonPath("$.code").value("IDEMPOTENCY_KEY_CONFLICT"))
        assertEquals(1, count("reservations"))
        assertEquals(1, count("reservation_creation_records"))
    }

    @Test
    fun `실패한 생성은 키를 남기지 않고 신규 생성은 입장 허가를 요구한다`() {
        mockMvc.perform(request("rollback", listOf(seatIds[0])))
            .andExpect(status().isTooManyRequests).andExpect(jsonPath("$.code").value("QUEUE_ADMISSION_REQUIRED"))
        mockMvc.perform(request("rollback", listOf(Long.MAX_VALUE), permit())).andExpect(status().isConflict)
        assertEquals(0, count("reservation_creation_records"))
        mockMvc.perform(request("rollback", listOf(seatIds[0]), permit())).andExpect(status().isCreated)
        assertEquals(1, count("reservations"))
    }

    @Test
    fun `입장권을 소비한 요청이 처리 중이면 중복 요청은 거절하고 커밋 후 최초 응답을 복구한다`() {
        val selected = seatIds.take(2)
        val ticket = permit()
        val key = "queue-concurrent"
        val executor = Executors.newFixedThreadPool(2)
        try {
            val pending = checkNotNull(TransactionTemplate(transactionManager).execute {
                jdbcTemplate.queryForObject(
                    "SELECT id FROM performance_seats WHERE id = ? FOR NO KEY UPDATE",
                    Long::class.java,
                    selected.first()
                )
                val blockerPid = checkNotNull(jdbcTemplate.queryForObject("SELECT pg_backend_pid()", Int::class.java))
                val first = executor.submit<MockHttpServletResponse> {
                    mockMvc.perform(request(key, selected, ticket))
                        .andExpect(status().isCreated).andReturn().response
                }
                // 첫 요청은 입장권과 키를 확보한 뒤 좌석 갱신에서 대기한다.
                jdbcTemplate.awaitBlockedTransaction(blockerPid)
                executor.submit {
                    mockMvc.perform(request(key, selected.reversed(), ticket))
                        .andExpect(status().isTooManyRequests)
                        .andExpect(jsonPath("$.code").value("QUEUE_ADMISSION_REQUIRED"))
                }.get(5, TimeUnit.SECONDS)
                first
            })

            val first = pending.get(5, TimeUnit.SECONDS)
            val retry = mockMvc.perform(request(key, selected.reversed()))
                .andExpect(status().isCreated).andReturn().response
            assertEquals(first.getHeader("Location"), retry.getHeader("Location"))
            assertEquals(first.contentAsString, retry.contentAsString)
            assertEquals(1, count("reservations"))
            assertEquals(1, count("reservation_creation_records"))
            assertEquals(
                2,
                jdbcTemplate.queryForObject("SELECT sum(allocated_quantity) FROM concert_purchase_quotas", Int::class.java)
            )
            assertEquals(
                2,
                jdbcTemplate.queryForObject("SELECT count(*) FROM performance_seats WHERE status = 'HELD'", Int::class.java)
            )
        } finally {
            executor.shutdownNow()
            assertTrue(executor.awaitTermination(10, TimeUnit.SECONDS))
        }
    }

    @Test
    fun `동시 재전달은 한 번만 선점하고 다른 사용자는 같은 키를 사용할 수 있다`() {
        val command = CreateReservationCommand(1, performanceId, seatIds.take(2), "concurrent")
        val barrier = CyclicBarrier(2)
        val executor = Executors.newFixedThreadPool(2)
        try {
            val results = List(2) {
                executor.submit<CreateReservationResult> {
                    barrier.await(5, TimeUnit.SECONDS)
                    createReservationUseCase.create(command)
                }
            }.map { it.get(10, TimeUnit.SECONDS) }
            assertEquals(results[0], results[1])
            assertEquals(1, count("reservations"))
            assertEquals(
                2,
                jdbcTemplate.queryForObject("SELECT sum(allocated_quantity) FROM concert_purchase_quotas", Int::class.java)
            )
            val other = createReservationUseCase.create(command.copy(userId = 2, seatIds = seatIds.takeLast(1)))
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

    private fun permit() = joinQueueUseCase.join(1).ticketId.toString()
    private fun count(table: String) = jdbcTemplate.queryForObject("SELECT count(*) FROM $table", Int::class.java)!!
}
