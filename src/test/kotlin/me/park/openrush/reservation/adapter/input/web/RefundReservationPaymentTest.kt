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
class RefundReservationPaymentTest {
    @Autowired
    private lateinit var controller: ReservationPaymentController

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
        mvc = MockMvcBuilders.standaloneSetup(controller).setControllerAdvice(advice).build()
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
    }

    @Test
    fun `예약 확정 실패 후 별도 API 없이 승인 흐름에서 바로 환불한다`() {
        respond = {
            when {
                it.requestMethod == "GET" -> it.reply(404, "{}")
                it.requestURI.path.endsWith("/cancel") -> it.reply(200, canceled())
                else -> {
                    expire()
                    it.reply(200, done())
                }
            }
        }
        confirm().andExpect(status().isOk).andExpect(jsonPath("$.status").value("REFUNDED"))
        assertEquals(listOf("GET", "POST", "POST"), requests.map { it.first })
        assertEquals(1, count("APPROVED"))
        assertEquals(1, count("CANCEL_PREPARED"))
        assertEquals(1, count("CANCELED"))
        confirm().andExpect(status().isOk).andExpect(jsonPath("$.status").value("REFUNDED"))
        assertEquals(3, requests.size)
    }

    @Test
    fun `환불 필요 결제는 준비를 커밋하고 PG 전액 취소 뒤 완료를 한 번만 기록한다`() {
        prepareRefund()
        val originalState = reservationStatus()
        respond = {
            assertEquals(1, count("CANCEL_PREPARED"))
            assertEquals(0, count("CANCELED"))
            it.reply(200, canceled())
        }
        refund().andExpect(status().isOk).andExpect(jsonPath("$.status").value("REFUNDED"))

        val request = requests.single()
        assertEquals("POST", request.first)
        assertTrue(request.second.startsWith("/v1/payments/PK-TEST/cancel|cancel-"))
        assertTrue(request.second.contains("\"cancelAmount\":15000"))
        assertTrue(request.second.contains("\"cancelReason\":\"RESERVATION_UNAVAILABLE\""))
        refund().andExpect(status().isOk)
        assertEquals(1, requests.size)
        assertEquals(1, count("CANCEL_PREPARED"))
        assertEquals(1, count("CANCELED"))
        assertEquals(originalState, reservationStatus())
    }

    @Test
    fun `환불 장애는 대기로 남기고 같은 멱등 키와 본문으로 재요청한다`() {
        prepareRefund()
        respond = { it.reply(503, "{}") }
        refund().andExpect(status().isOk).andExpect(jsonPath("$.status").value("REFUND_REQUIRED"))
        assertEquals(1, requests.size)
        assertEquals(0, count("CANCELED"))
        val first = requests.single()
        respond = { it.reply(200, canceled()) }
        refund().andExpect(status().isOk)
        assertEquals(first, requests.last())
        assertEquals(1, count("CANCEL_PREPARED"))
        assertEquals(1, count("CANCELED"))
    }

    @Test
    fun `환불 응답 유실 뒤 재요청도 같은 요청으로 완료한다`() {
        prepareRefund()
        val release = CountDownLatch(1)
        respond = {
            release.await(3, TimeUnit.SECONDS)
            runCatching { it.reply(200, canceled()) }
        }
        try {
            refund().andExpect(status().isOk).andExpect(jsonPath("$.status").value("REFUND_REQUIRED"))
        } finally {
            release.countDown()
        }
        val first = requests.single()
        respond = { it.reply(200, canceled()) }
        refund().andExpect(status().isOk)
        assertEquals(first, requests.last())
        assertEquals(1, count("CANCELED"))
    }

    @Test
    fun `PG 환불 거절에는 완료 이력을 저장하지 않는다`() {
        prepareRefund()
        respond = { it.reply(400, "{\"code\":\"NOT_CANCELABLE_PAYMENT\"}") }
        refund().andExpect(status().isOk).andExpect(jsonPath("$.status").value("REFUND_REQUIRED"))
        assertEquals(1, count("CANCEL_PREPARED"))
        assertEquals(0, count("CANCELED"))
    }

    @Test
    fun `환불 응답의 결제나 취소 정보가 다르면 완료로 기록하지 않는다`() {
        prepareRefund()
        val invalid = listOf(
            canceled().replace("PK-TEST", "OTHER-KEY"),
            canceled().replace(orderId, "OTHER-ORDER"),
            canceled().replace("CANCELED", "PARTIAL_CANCELED"),
            canceled().replace("\"balanceAmount\":0", "\"balanceAmount\":1"),
            canceled().replace("\"cancelAmount\":15000", "\"cancelAmount\":14000"),
            canceled().replace("RESERVATION_UNAVAILABLE", "OTHER-REASON"),
            canceled().replace("\"cancelStatus\":\"DONE\"", "\"cancelStatus\":\"FAILED\""),
        )
        invalid.forEach { body ->
            respond = { it.reply(200, body) }
            refund().andExpect(status().isOk).andExpect(jsonPath("$.status").value("REFUND_REQUIRED"))
        }
        assertEquals(0, count("CANCELED"))
    }

    @Test
    fun `타인이나 승인 없는 시도 또는 확정된 예약은 환불할 수 없다`() {
        fun internalRefund(userId: Long = 1) = refunds.refund(
            me.park.openrush.reservation.application.provided.RefundReservationPaymentCommand(
                reservationId, userId, attemptNumber,
            ),
        )
        assertFailsWith<me.park.openrush.reservation.application.exception.PaymentRecordConflictException> { internalRefund() }
        normalResponses()
        confirm().andExpect(status().isOk)
        requests.clear()
        assertFailsWith<me.park.openrush.reservation.application.exception.PaymentRecordConflictException> {
            internalRefund(
                2
            )
        }
        assertFailsWith<me.park.openrush.reservation.application.exception.PaymentRecordConflictException> { internalRefund() }
        assertTrue(requests.isEmpty())
        assertEquals(0, count("CANCEL_PREPARED"))
    }

    @Test
    fun `환불 결과 저장 실패 후에도 준비 이력과 동일한 PG 요청으로 복구한다`() {
        prepareRefund()
        respond = { it.reply(200, canceled()) }
        jdbc.execute(
            """
            CREATE FUNCTION reject_test_refund() RETURNS trigger LANGUAGE plpgsql AS $$
            BEGIN
                IF NEW.type = 'CANCELED' THEN
                    RAISE EXCEPTION 'forced failure' USING ERRCODE = '23514';
                END IF;
                RETURN NEW;
            END $$
            """.trimIndent()
        )
        jdbc.execute("CREATE TRIGGER reject_test_refund BEFORE INSERT ON reservation_payment_tx FOR EACH ROW EXECUTE FUNCTION reject_test_refund()")
        try {
            assertFailsWith<ServletException> { refund() }
            assertEquals(1, count("CANCEL_PREPARED"))
            assertEquals(0, count("CANCELED"))
        } finally {
            jdbc.execute("DROP TRIGGER reject_test_refund ON reservation_payment_tx")
            jdbc.execute("DROP FUNCTION reject_test_refund()")
        }
        refund().andExpect(status().isOk)
        assertEquals(requests.first(), requests.last())
        assertEquals(1, count("CANCELED"))
    }

    @Test
    fun `동시 환불은 같은 멱등 키를 사용하고 완료는 한 행만 기록한다`() {
        prepareRefund()
        val callers = Executors.newFixedThreadPool(2)
        val posts = CountDownLatch(2)
        respond = {
            posts.countDown()
            assertTrue(posts.await(5, TimeUnit.SECONDS))
            it.reply(200, canceled())
        }
        try {
            val results = List(2) { callers.submit<Int> { refund().andReturn().response.status } }
            assertEquals(listOf(200, 200), results.map { it.get(10, TimeUnit.SECONDS) })
            assertEquals(1, requests.distinct().size)
            assertEquals(1, count("CANCEL_PREPARED"))
            assertEquals(1, count("CANCELED"))
        } finally {
            callers.shutdownNow()
        }
    }

    private fun prepareRefund() {
        normalResponses(beforeApproval = { expire() })
        confirm().andExpect(status().isOk).andExpect(jsonPath("$.status").value("REFUND_REQUIRED"))
        requests.clear()
    }

    private fun refund(userId: Long = 1) = mvc.perform(
        post("/reservations/$reservationId/confirm").contentType(MediaType.APPLICATION_JSON)
            .content("""{"userId":$userId,"attemptNumber":"$attemptNumber","paymentKey":"PK-TEST"}"""),
    )

    private fun canceled() =
        """{"paymentKey":"PK-TEST","orderId":"$orderId","currency":"KRW","totalAmount":15000,"balanceAmount":0,"status":"CANCELED","cancels":[{"transactionKey":"CANCEL-TEST","cancelAmount":15000,"cancelReason":"RESERVATION_UNAVAILABLE","cancelStatus":"DONE"}]}"""

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
