package me.park.openrush.reservation.adapter.input.web

import jakarta.servlet.ServletException
import me.park.openrush.support.database.DatabaseIntegrationTest
import me.park.openrush.support.fixture.ConcertFixture
import me.park.openrush.support.fixture.InventoryFixture
import me.park.openrush.support.pg.DemoPgStubServer
import me.park.openrush.support.pg.reply
import me.park.openrush.reservation.application.provided.CreateReservationCommand
import me.park.openrush.reservation.application.provided.CreateReservationUseCase
import me.park.openrush.support.web.GlobalExceptionHandler
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.http.MediaType
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.test.web.servlet.setup.MockMvcBuilders
import java.util.*
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

@DatabaseIntegrationTest
class RefundReservationPaymentTest {
    @Autowired
    private lateinit var reservationPaymentController: ReservationPaymentController

    @Autowired
    private lateinit var refundReservationPaymentUseCase: me.park.openrush.reservation.application.provided.RefundReservationPaymentUseCase

    @Autowired
    private lateinit var globalExceptionHandler: GlobalExceptionHandler

    @Autowired
    private lateinit var createReservationUseCase: CreateReservationUseCase

    @Autowired
    private lateinit var jdbcTemplate: JdbcTemplate
    private lateinit var mockMvc: MockMvc
    private var reservationId = 0L
    private lateinit var attemptNumber: UUID
    private val orderId: String
        get() = jdbcTemplate.queryForObject(
            "SELECT pg_order_number FROM reservation_payment_tx WHERE type = 'APPROVAL_PREPARED'",
            String::class.java
        )!!

    @BeforeEach
    fun setUp() {
        demoPgStubServer.reset()
        mockMvc = MockMvcBuilders.standaloneSetup(reservationPaymentController).setControllerAdvice(globalExceptionHandler).build()
        val concertFixture = ConcertFixture(jdbcTemplate)
        val concertId = concertFixture.createConcert()
        val performanceId = concertFixture.createPerformance(concertId)
        val seatId = InventoryFixture(jdbcTemplate).createSeat(performanceId)
        reservationId = createReservationUseCase.create(CreateReservationCommand(1L, performanceId, listOf(seatId))).reservationId
        attemptNumber = UUID.randomUUID()
    }

    @Test
    fun `예약 확정 실패 후 별도 API 없이 승인 흐름에서 바로 환불한다`() {
        demoPgStubServer.respond = {
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
        assertEquals(listOf("GET", "POST", "POST"), demoPgStubServer.requests.map { it.method })
        assertEquals(1, count("APPROVED"))
        assertEquals(1, count("CANCEL_PREPARED"))
        assertEquals(1, count("CANCELED"))
        confirm().andExpect(status().isOk).andExpect(jsonPath("$.status").value("REFUNDED"))
        assertEquals(3, demoPgStubServer.requests.size)
    }

    @Test
    fun `환불 필요 결제는 준비를 커밋하고 PG 전액 취소 뒤 완료를 한 번만 기록한다`() {
        prepareRefund()
        val originalState = reservationStatus()
        demoPgStubServer.respond = {
            assertEquals(1, count("CANCEL_PREPARED"))
            assertEquals(0, count("CANCELED"))
            it.reply(200, canceled())
        }
        refund().andExpect(status().isOk).andExpect(jsonPath("$.status").value("REFUNDED"))

        val request = demoPgStubServer.requests.single()
        assertEquals("POST", request.method)
        assertEquals("/v1/payments/PK-TEST/cancel", request.path)
        assertTrue(request.idempotencyKey!!.startsWith("cancel-"))
        assertTrue(request.body.contains("\"cancelAmount\":15000"))
        assertTrue(request.body.contains("\"cancelReason\":\"RESERVATION_UNAVAILABLE\""))
        refund().andExpect(status().isOk)
        assertEquals(1, demoPgStubServer.requests.size)
        assertEquals(1, count("CANCEL_PREPARED"))
        assertEquals(1, count("CANCELED"))
        assertEquals(originalState, reservationStatus())
    }

    @Test
    fun `환불 장애는 대기로 남기고 같은 멱등 키와 본문으로 재요청한다`() {
        prepareRefund()
        demoPgStubServer.respond = { it.reply(503, "{}") }
        refund().andExpect(status().isOk).andExpect(jsonPath("$.status").value("REFUND_REQUIRED"))
        assertEquals(1, demoPgStubServer.requests.size)
        assertEquals(0, count("CANCELED"))
        val first = demoPgStubServer.requests.single()
        demoPgStubServer.respond = { it.reply(200, canceled()) }
        refund().andExpect(status().isOk)
        assertTrue(first.hasSameContentAs(demoPgStubServer.requests.last()))
        assertEquals(1, count("CANCEL_PREPARED"))
        assertEquals(1, count("CANCELED"))
    }

    @Test
    fun `환불 응답 유실 뒤 재요청도 같은 요청으로 완료한다`() {
        prepareRefund()
        val release = CountDownLatch(1)
        demoPgStubServer.respond = {
            release.await(3, TimeUnit.SECONDS)
            runCatching { it.reply(200, canceled()) }
        }
        try {
            refund().andExpect(status().isOk).andExpect(jsonPath("$.status").value("REFUND_REQUIRED"))
        } finally {
            release.countDown()
        }
        val first = demoPgStubServer.requests.single()
        demoPgStubServer.respond = { it.reply(200, canceled()) }
        refund().andExpect(status().isOk)
        assertTrue(first.hasSameContentAs(demoPgStubServer.requests.last()))
        assertEquals(1, count("CANCELED"))
    }

    @Test
    fun `PG 환불 거절에는 완료 이력을 저장하지 않는다`() {
        prepareRefund()
        demoPgStubServer.respond = { it.reply(400, "{\"code\":\"NOT_CANCELABLE_PAYMENT\"}") }
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
            demoPgStubServer.respond = { it.reply(200, body) }
            refund().andExpect(status().isOk).andExpect(jsonPath("$.status").value("REFUND_REQUIRED"))
        }
        assertEquals(0, count("CANCELED"))
    }

    @Test
    fun `타인이나 승인 없는 시도 또는 확정된 예약은 환불할 수 없다`() {
        fun internalRefund(userId: Long = 1) = refundReservationPaymentUseCase.refund(
            me.park.openrush.reservation.application.provided.RefundReservationPaymentCommand(
                reservationId, userId, attemptNumber,
            ),
        )
        assertFailsWith<me.park.openrush.reservation.application.exception.PaymentRecordConflictException> { internalRefund() }
        normalResponses()
        confirm().andExpect(status().isOk)
        demoPgStubServer.requests.clear()
        assertFailsWith<me.park.openrush.reservation.application.exception.PaymentRecordConflictException> {
            internalRefund(
                2
            )
        }
        assertFailsWith<me.park.openrush.reservation.application.exception.PaymentRecordConflictException> { internalRefund() }
        assertTrue(demoPgStubServer.requests.isEmpty())
        assertEquals(0, count("CANCEL_PREPARED"))
    }

    @Test
    fun `환불 결과 저장 실패 후에도 준비 이력과 동일한 PG 요청으로 복구한다`() {
        prepareRefund()
        demoPgStubServer.respond = { it.reply(200, canceled()) }
        jdbcTemplate.execute(
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
        jdbcTemplate.execute("CREATE TRIGGER reject_test_refund BEFORE INSERT ON reservation_payment_tx FOR EACH ROW EXECUTE FUNCTION reject_test_refund()")
        try {
            assertFailsWith<ServletException> { refund() }
            assertEquals(1, count("CANCEL_PREPARED"))
            assertEquals(0, count("CANCELED"))
        } finally {
            jdbcTemplate.execute("DROP TRIGGER reject_test_refund ON reservation_payment_tx")
            jdbcTemplate.execute("DROP FUNCTION reject_test_refund()")
        }
        refund().andExpect(status().isOk)
        assertTrue(demoPgStubServer.requests.first().hasSameContentAs(demoPgStubServer.requests.last()))
        assertEquals(1, count("CANCELED"))
    }

    @Test
    fun `동시 환불은 같은 멱등 키를 사용하고 완료는 한 행만 기록한다`() {
        prepareRefund()
        val callers = Executors.newFixedThreadPool(2)
        val posts = CountDownLatch(2)
        demoPgStubServer.respond = {
            posts.countDown()
            assertTrue(posts.await(5, TimeUnit.SECONDS))
            it.reply(200, canceled())
        }
        try {
            val results = List(2) { callers.submit<Int> { refund().andReturn().response.status } }
            assertEquals(listOf(200, 200), results.map { it.get(10, TimeUnit.SECONDS) })
            assertTrue(demoPgStubServer.requests.isNotEmpty())
            assertTrue(demoPgStubServer.requests.all { it.hasSameContentAs(demoPgStubServer.requests.first()) })
            assertEquals(1, count("CANCEL_PREPARED"))
            assertEquals(1, count("CANCELED"))
        } finally {
            callers.shutdownNow()
        }
    }

    private fun prepareRefund() {
        normalResponses(beforeApproval = { expire() })
        confirm().andExpect(status().isOk).andExpect(jsonPath("$.status").value("REFUND_REQUIRED"))
        demoPgStubServer.requests.clear()
    }

    private fun refund(userId: Long = 1) = mockMvc.perform(
        post("/reservations/$reservationId/confirm").contentType(MediaType.APPLICATION_JSON)
            .content("""{"userId":$userId,"attemptNumber":"$attemptNumber","paymentKey":"PK-TEST"}"""),
    )

    private fun canceled() =
        """{"paymentKey":"PK-TEST","orderId":"$orderId","currency":"KRW","totalAmount":15000,"balanceAmount":0,"status":"CANCELED","cancels":[{"transactionKey":"CANCEL-TEST","cancelAmount":15000,"cancelReason":"RESERVATION_UNAVAILABLE","cancelStatus":"DONE"}]}"""

    private fun normalResponses(beforeApproval: () -> Unit = {}, response: () -> String = { done() }) {
        demoPgStubServer.respond = { exchange ->
            if (exchange.requestMethod == "GET") exchange.reply(404, "{}")
            else {
                beforeApproval()
                exchange.reply(200, response())
            }
        }
    }

    private fun confirm(key: String = "PK-TEST", userId: Long = 1) = mockMvc.perform(
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
        jdbcTemplate.update(
            "UPDATE reservations SET held_at = now() - interval '6 minutes', expires_at = now() - interval '1 minute' WHERE id = ?",
            reservationId
        )
    }

    private fun count(type: String) =
        jdbcTemplate.queryForObject("SELECT count(*) FROM reservation_payment_tx WHERE type = ?", Int::class.java, type)

    private fun reservationStatus() =
        jdbcTemplate.queryForObject("SELECT status FROM reservations WHERE id = ?", String::class.java, reservationId)

    companion object {
        private val demoPgStubServer = DemoPgStubServer()

        @JvmStatic
        @DynamicPropertySource
        fun properties(registry: DynamicPropertyRegistry) {
            registry.add("payment.demo-pg.base-url") { demoPgStubServer.baseUrl }
        }

        @JvmStatic
        @AfterAll
        fun stopServer() {
            demoPgStubServer.close()
        }
    }
}
