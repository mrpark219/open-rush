package me.park.openrush.reservation.adapter.input.web

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import jakarta.servlet.ServletException
import me.park.openrush.TestcontainersConfiguration
import me.park.openrush.reservation.application.provided.CreateReservationCommand
import me.park.openrush.reservation.application.provided.CreateReservationUseCase
import me.park.openrush.support.web.GlobalExceptionHandler
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.http.MediaType
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.test.web.servlet.setup.MockMvcBuilders
import java.net.InetSocketAddress
import java.util.*
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

@Import(TestcontainersConfiguration::class)
@SpringBootTest(properties = ["reservation.expiration.enabled=false"])
class CancelReservationTest {
    @Autowired
    private lateinit var controller: ReservationPaymentController

    @Autowired
    private lateinit var reservationController: ReservationController

    @Autowired
    private lateinit var confirmReservationUseCase: me.park.openrush.reservation.application.provided.ConfirmReservationUseCase

    @Autowired
    private lateinit var refunds: me.park.openrush.reservation.application.provided.RefundReservationPaymentUseCase

    @Autowired
    private lateinit var advice: GlobalExceptionHandler

    @Autowired
    private lateinit var reservations: CreateReservationUseCase

    @Autowired
    private lateinit var jdbc: JdbcTemplate
    private lateinit var mvc: MockMvc
    private var reservationId = 0L
    private lateinit var attemptNumber: UUID
    private val orderId: String
        get() = jdbc.queryForObject(
            "SELECT pg_order_number FROM reservation_payment_tx WHERE type = 'APPROVAL_PREPARED'",
            String::class.java
        )!!

    @BeforeEach
    fun setUp() {
        requests.clear()
        respond = { it.reply(500, "{}") }
        mvc = MockMvcBuilders.standaloneSetup(controller, reservationController).setControllerAdvice(advice).build()
        jdbc.execute("TRUNCATE concerts CASCADE")
        val concertId =
            id("INSERT INTO concerts(title, description, max_tickets_per_user) VALUES ('공연', '', 4) RETURNING id")
        val performanceId = id(
            """
            INSERT INTO performances(concert_id, venue, starts_at, booking_opens_at, booking_closes_at)
            VALUES (?, '공연장', now() + interval '2 days', now() - interval '1 day', now() + interval '1 day') RETURNING id
        """.trimIndent(), concertId
        )
        val seatId = id(
            "INSERT INTO performance_seats(performance_id, seat_number, price, status) VALUES (?, 'A-1', 15000, 'AVAILABLE') RETURNING id",
            performanceId
        )
        reservationId = reservations.create(CreateReservationCommand(1L, performanceId, listOf(seatId))).reservationId
        attemptNumber = UUID.randomUUID()
        normalResponses()
        confirm().andExpect(status().isOk)
        requests.clear()
    }

    @Test
    fun `예약 취소와 자원 반환을 먼저 커밋하고 환불하며 재요청은 중복 반환하지 않는다`() {
        respond = {
            assertEquals("CANCELLED", reservationStatus())
            assertEquals("AVAILABLE", seatStatus())
            assertEquals(0, allocatedQuantity())
            assertEquals(1, count("CANCEL_PREPARED"))
            it.reply(200, canceled())
        }
        cancel().andExpect(status().isOk)
            .andExpect(jsonPath("$.status").value("CANCELLED"))
            .andExpect(jsonPath("$.refundStatus").value("REFUNDED"))
        assertEquals(1, count("CANCELED"))
        assertTrue(jdbc.queryForObject("SELECT confirmed_at IS NOT NULL FROM reservations WHERE id = ?", Boolean::class.java, reservationId)!!)
        val performanceId = jdbc.queryForObject("SELECT performance_id FROM reservations WHERE id = ?", Long::class.java, reservationId)!!
        val seatId = jdbc.queryForObject("SELECT id FROM performance_seats", Long::class.java)!!
        val newReservationId = reservations.create(CreateReservationCommand(1L, performanceId, listOf(seatId))).reservationId
        cancel().andExpect(status().isOk)
        assertEquals(1, requests.size)
        assertEquals("HELD", seatStatus())
        assertEquals(newReservationId, jdbc.queryForObject("SELECT reservation_id FROM performance_seats", Long::class.java))
        assertEquals(1, allocatedQuantity())
        assertEquals(1, count("CANCEL_PREPARED"))
    }

    @Test
    fun `PG 장애에도 취소와 반환은 유지하고 같은 요청으로 환불만 다시 시도한다`() {
        respond = { it.reply(503, "{}") }
        cancel().andExpect(status().isOk).andExpect(jsonPath("$.refundStatus").value("PENDING"))
        assertEquals("CANCELLED", reservationStatus())
        assertEquals("AVAILABLE", seatStatus())
        assertEquals(0, allocatedQuantity())
        assertEquals(0, count("CANCELED"))
        val firstRequest = requests.single()
        respond = { it.reply(200, canceled()) }
        cancel().andExpect(status().isOk).andExpect(jsonPath("$.refundStatus").value("REFUNDED"))
        assertEquals(firstRequest, requests.last())
        assertEquals(1, count("CANCEL_PREPARED"))
        assertEquals(1, count("CANCELED"))
    }

    @Test
    fun `PG가 취소를 거절해도 예약은 취소 상태로 남는다`() {
        respond = { it.reply(400, "{}") }
        cancel().andExpect(status().isOk).andExpect(jsonPath("$.refundStatus").value("PENDING"))
        assertEquals("CANCELLED", reservationStatus())
        assertEquals(0, allocatedQuantity())
        assertEquals(0, count("CANCELED"))
    }

    @Test
    fun `다른 사용자와 다른 취소 사유의 재요청은 거절한다`() {
        cancel(userId = 2).andExpect(status().isConflict)
        assertEquals("CONFIRMED", reservationStatus())
        assertTrue(requests.isEmpty())
        respond = { it.reply(200, canceled()) }
        cancel().andExpect(status().isOk)
        cancel(reason = "OTHER-REASON").andExpect(status().isConflict)
        assertEquals(1, requests.size)
    }

    @Test
    fun `좌석 반환 실패 시 취소와 한도 반환 및 환불 준비를 모두 롤백한다`() {
        jdbc.update("UPDATE performance_seats SET status = 'AVAILABLE', reservation_id = NULL")
        assertFailsWith<ServletException> { cancel() }
        assertEquals("CONFIRMED", reservationStatus())
        assertEquals(1, allocatedQuantity())
        assertEquals(0, count("CANCEL_PREPARED"))
        assertTrue(requests.isEmpty())
    }

    @Test
    fun `환불 완료 저장이 실패해도 예약 취소는 유지하며 재요청으로 복구한다`() {
        respond = { it.reply(200, canceled()) }
        jdbc.execute("""
            CREATE FUNCTION reject_cancel_result() RETURNS trigger LANGUAGE plpgsql AS $$
            BEGIN
                IF NEW.type = 'CANCELED' THEN
                    RAISE EXCEPTION 'forced failure' USING ERRCODE = '23514';
                END IF;
                RETURN NEW;
            END $$
        """.trimIndent())
        jdbc.execute("CREATE TRIGGER reject_cancel_result BEFORE INSERT ON reservation_payment_tx FOR EACH ROW EXECUTE FUNCTION reject_cancel_result()")
        try {
            assertFailsWith<ServletException> { cancel() }
            assertEquals("CANCELLED", reservationStatus())
            assertEquals(0, allocatedQuantity())
            assertEquals(1, count("CANCEL_PREPARED"))
            assertEquals(0, count("CANCELED"))
        } finally {
            jdbc.execute("DROP TRIGGER reject_cancel_result ON reservation_payment_tx")
            jdbc.execute("DROP FUNCTION reject_cancel_result()")
        }
        cancel().andExpect(status().isOk)
        assertEquals(requests.first(), requests.last())
        assertEquals(1, count("CANCELED"))
    }

    @Test
    fun `동시 예약 취소도 자원은 한 번 반환하고 동일한 환불 요청을 사용한다`() {
        val callers = Executors.newFixedThreadPool(2)
        val posts = CountDownLatch(2)
        respond = {
            posts.countDown()
            assertTrue(posts.await(5, TimeUnit.SECONDS))
            it.reply(200, canceled())
        }
        try {
            val results = List(2) { callers.submit<Int> { cancel().andReturn().response.status } }
            assertEquals(listOf(200, 200), results.map { it.get(10, TimeUnit.SECONDS) })
            assertEquals(0, allocatedQuantity())
            assertEquals(1, requests.distinct().size)
            assertEquals(1, count("CANCEL_PREPARED"))
            assertEquals(1, count("CANCELED"))
        } finally {
            callers.shutdownNow()
        }
    }

    @Test
    fun `취소한 예약의 승인 재요청은 다시 확정하지 않는다`() {
        respond = { it.reply(200, canceled()) }
        cancel().andExpect(status().isOk)
        confirm().andExpect(status().isConflict)
        assertEquals("CANCELLED", reservationStatus())
        assertEquals(1, requests.size)
    }

    @Test
    fun `잘못된 취소 입력은 자원 변경이나 PG 호출 전에 거절한다`() {
        cancel(reason = " ").andExpect(status().isBadRequest)
        cancel(reason = "X".repeat(256)).andExpect(status().isBadRequest)
        cancel(userId = 0).andExpect(status().isBadRequest)
        assertEquals("CONFIRMED", reservationStatus())
        assertEquals(1, allocatedQuantity())
        assertTrue(requests.isEmpty())
    }

    @Test
    fun `선점 예약은 확정 예약 취소 엔드포인트에서 거절한다`() {
        val performanceId = jdbc.queryForObject("SELECT performance_id FROM reservations WHERE id = ?", Long::class.java, reservationId)!!
        val heldSeatId = id("INSERT INTO performance_seats(performance_id, seat_number, price, status) VALUES (?, 'A-2', 15000, 'AVAILABLE') RETURNING id", performanceId)
        val heldReservationId = reservations.create(CreateReservationCommand(1L, performanceId, listOf(heldSeatId))).reservationId
        cancel(targetReservationId = heldReservationId).andExpect(status().isConflict)
        assertEquals("HELD", jdbc.queryForObject("SELECT status FROM reservations WHERE id = ?", String::class.java, heldReservationId))
        assertTrue(requests.isEmpty())
    }

    @Test
    fun `확정된 무료 예약은 환불 없이 취소하고 자원을 반환한다`() {
        val performanceId = jdbc.queryForObject("SELECT performance_id FROM reservations WHERE id = ?", Long::class.java, reservationId)!!
        val freeSeatId = id("INSERT INTO performance_seats(performance_id, seat_number, price, status) VALUES (?, 'FREE-1', 0, 'AVAILABLE') RETURNING id", performanceId)
        val freeReservationId = reservations.create(CreateReservationCommand(2L, performanceId, listOf(freeSeatId))).reservationId
        confirmReservationUseCase.confirm(me.park.openrush.reservation.application.provided.ConfirmReservationCommand(2L, freeReservationId))
        cancel(userId = 2, targetReservationId = freeReservationId).andExpect(status().isOk)
            .andExpect(jsonPath("$.status").value("CANCELLED"))
            .andExpect(jsonPath("$.refundStatus").doesNotExist())
        assertEquals("AVAILABLE", jdbc.queryForObject("SELECT status FROM performance_seats WHERE id = ?", String::class.java, freeSeatId))
        assertEquals(0, jdbc.queryForObject("SELECT allocated_quantity FROM concert_purchase_quotas WHERE user_id = 2", Int::class.java))
        assertTrue(requests.isEmpty())
        assertEquals(0, count("CANCEL_PREPARED"))
    }

    private fun cancel(userId: Long = 1, reason: String = "USER_REQUEST", targetReservationId: Long = reservationId) = mvc.perform(
        post("/reservations/$targetReservationId/cancel").contentType(MediaType.APPLICATION_JSON)
            .content("""{"userId":$userId,"cancelReason":"$reason"}"""),
    )

    private fun allocatedQuantity() = jdbc.queryForObject("SELECT allocated_quantity FROM concert_purchase_quotas", Int::class.java)
    private fun seatStatus() = jdbc.queryForObject("SELECT status FROM performance_seats", String::class.java)

    private fun canceled() =
        """{"paymentKey":"PK-TEST","orderId":"$orderId","currency":"KRW","totalAmount":15000,"balanceAmount":0,"status":"CANCELED","cancels":[{"transactionKey":"CANCEL-TEST","cancelAmount":15000,"cancelReason":"USER_REQUEST","cancelStatus":"DONE"}]}"""

    private fun normalResponses(beforeApproval: () -> Unit = {}, response: () -> String = { done() }) {
        respond = { exchange ->
            if (exchange.requestMethod == "GET") exchange.reply(404, "{}")
            else {
                beforeApproval()
                exchange.reply(200, response())
            }
        }
    }

    private fun confirm(key: String = "PK-TEST", userId: Long = 1) = mvc.perform(
        post("/reservations/$reservationId/confirm").contentType(MediaType.APPLICATION_JSON)
            .content("""{"userId":$userId,"attemptNumber":"$attemptNumber","paymentKey":"$key"}"""),
    )

    private fun done(
        order: String = orderId,
        key: String = "PK-TEST",
        state: String = "DONE",
        amount: Long = 15000,
        currency: String = "KRW"
    ) =
        """{"paymentKey":"$key","orderId":"$order","currency":"$currency","totalAmount":$amount,"balanceAmount":$amount,"status":"$state"}"""

    private fun expire() {
        jdbc.update(
            "UPDATE reservations SET held_at = now() - interval '6 minutes', expires_at = now() - interval '1 minute' WHERE id = ?",
            reservationId
        )
    }

    private fun count(type: String) =
        jdbc.queryForObject("SELECT count(*) FROM reservation_payment_tx WHERE type = ?", Int::class.java, type)

    private fun reservationStatus() =
        jdbc.queryForObject("SELECT status FROM reservations WHERE id = ?", String::class.java, reservationId)

    private fun id(sql: String, vararg args: Any): Long = jdbc.queryForObject(sql, Long::class.java, *args)!!

    companion object {
        private val requests = CopyOnWriteArrayList<Pair<String, String>>()

        @Volatile
        private var respond: (HttpExchange) -> Unit = { it.reply(500, "{}") }
        private val pgExecutor = Executors.newCachedThreadPool()
        private val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
            createContext("/v1/payments") { exchange ->
                val body = exchange.requestBody.bufferedReader().use { it.readText() }
                requests.add(
                    exchange.requestMethod to (exchange.requestURI.path + "|" + exchange.requestHeaders.getFirst(
                        "Idempotency-Key"
                    ) + "|" + body)
                )
                respond(exchange)
            }
            this.executor = pgExecutor
            start()
        }

        @JvmStatic
        @DynamicPropertySource
        fun properties(registry: DynamicPropertyRegistry) {
            registry.add("payment.demo-pg.base-url") { "http://127.0.0.1:${server.address.port}" }
        }

        @JvmStatic
        @AfterAll
        fun stop() {
            server.stop(0); pgExecutor.shutdownNow()
        }

        private fun HttpExchange.reply(status: Int, body: String) {
            val bytes = body.toByteArray()
            responseHeaders.set("Content-Type", "application/json")
            sendResponseHeaders(status, bytes.size.toLong())
            responseBody.use { it.write(bytes) }
        }
    }
}
