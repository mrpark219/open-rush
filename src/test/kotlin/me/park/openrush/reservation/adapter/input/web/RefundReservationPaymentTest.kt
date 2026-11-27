package me.park.openrush.reservation.adapter.input.web

import jakarta.servlet.ServletException
import me.park.openrush.support.database.DatabaseIntegrationTest
import me.park.openrush.support.fixture.ConcertFixture
import me.park.openrush.support.fixture.InventoryFixture
import me.park.openrush.support.pg.DemoPgStubServer
import me.park.openrush.support.pg.reply
import me.park.openrush.reservation.application.provided.ApproveReservationPaymentCommand
import me.park.openrush.reservation.application.provided.PrepareReservationPaymentTxUseCase
import me.park.openrush.reservation.application.provided.ProcessReservationRefundJobsUseCase
import me.park.openrush.reservation.application.provided.RecordPaymentSuccessCommand
import me.park.openrush.reservation.application.provided.RecordPaymentSuccessUseCase
import me.park.openrush.reservation.application.provided.CreateReservationCommand
import me.park.openrush.reservation.application.provided.CreateReservationUseCase
import me.park.openrush.support.web.GlobalExceptionHandler
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
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
    private lateinit var prepareReservationPaymentTxUseCase: PrepareReservationPaymentTxUseCase

    @Autowired
    private lateinit var recordPaymentSuccessUseCase: RecordPaymentSuccessUseCase

    @Autowired
    private lateinit var processReservationRefundJobsUseCase: ProcessReservationRefundJobsUseCase

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
                it.requestURI.path.endsWith("/cancel") -> {
                    assertEquals("PENDING", refundJobStatus())
                    it.reply(200, canceled())
                }
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
        assertEquals("COMPLETED", refundJobStatus())
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
        assertEquals("PENDING", refundJobStatus())
        val first = demoPgStubServer.requests.single()
        demoPgStubServer.respond = { it.reply(200, canceled()) }
        makeRefundDue()
        processReservationRefundJobsUseCase.processDueRefunds()
        assertTrue(first.hasSameContentAs(demoPgStubServer.requests.last()))
        assertEquals(1, count("CANCEL_PREPARED"))
        assertEquals(1, count("CANCELED"))
        assertEquals("COMPLETED", refundJobStatus())
        assertEquals(1, jdbcTemplate.queryForObject("SELECT count(*) FROM reservation_refund_jobs", Int::class.java))
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
        makeRefundDue()
        processReservationRefundJobsUseCase.processDueRefunds()
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

    @ParameterizedTest
    @ValueSource(ints = [0, 1, 2, 3, 4, 5, 6])
    fun `환불 응답의 결제나 취소 정보가 다르면 완료로 기록하지 않는다`(invalidResponseIndex: Int) {
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
        demoPgStubServer.respond = { it.reply(200, invalid[invalidResponseIndex]) }
        refund().andExpect(status().isOk).andExpect(jsonPath("$.status").value("REFUND_REQUIRED"))
        assertEquals(1, demoPgStubServer.requests.size)
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
        makeRefundDue()
        refund().andExpect(status().isOk)
        assertTrue(demoPgStubServer.requests.first().hasSameContentAs(demoPgStubServer.requests.last()))
        assertEquals(1, count("CANCELED"))
    }

    @Test
    fun `두 스케줄러가 동시에 실행되어도 환불을 한 번만 호출한다`() {
        prepareRefund()
        val caller = Executors.newSingleThreadExecutor()
        val posted = CountDownLatch(1)
        val release = CountDownLatch(1)
        demoPgStubServer.respond = {
            posted.countDown()
            assertTrue(release.await(5, TimeUnit.SECONDS))
            it.reply(200, canceled())
        }
        try {
            val first = caller.submit { processReservationRefundJobsUseCase.processDueRefunds() }
            assertTrue(posted.await(5, TimeUnit.SECONDS))
            processReservationRefundJobsUseCase.processDueRefunds()
            assertEquals(1, demoPgStubServer.requests.size)

            release.countDown()
            first.get(10, TimeUnit.SECONDS)
            assertEquals(1, count("CANCEL_PREPARED"))
            assertEquals(1, count("CANCELED"))
        } finally {
            release.countDown()
            caller.shutdownNow()
        }
    }

    @Test
    fun `일시 실패가 다섯 번 누적되면 같은 요청을 더 보내지 않고 수동 확인 대상으로 남긴다`() {
        prepareRefund()
        demoPgStubServer.respond = { it.reply(503, "{}") }

        repeat(5) {
            makeRefundDue()
            processReservationRefundJobsUseCase.processDueRefunds()
        }
        makeRefundDue()
        processReservationRefundJobsUseCase.processDueRefunds()

        assertEquals("NEEDS_REVIEW", refundJobStatus())
        assertEquals(5, jdbcTemplate.queryForObject("SELECT failure_count FROM reservation_refund_jobs", Int::class.java))
        assertEquals(5, demoPgStubServer.requests.size)
        assertTrue(demoPgStubServer.requests.all { it.hasSameContentAs(demoPgStubServer.requests.first()) })
        assertEquals(0, count("CANCELED"))
    }

    @Test
    fun `PG 주문 정보가 없는 과거 승인 작업은 PG 호출 없이 수동 확인 대상으로 남긴다`() {
        expire()
        recordPaymentSuccessUseCase.record(RecordPaymentSuccessCommand("demo-pg", "LEGACY", reservationId, 15000, "KRW"))

        processReservationRefundJobsUseCase.processDueRefunds()

        assertEquals("NEEDS_REVIEW", refundJobStatus())
        assertEquals("INVALID_PAYMENT_DATA", jdbcTemplate.queryForObject("SELECT last_failure FROM reservation_refund_jobs", String::class.java))
        assertTrue(demoPgStubServer.requests.isEmpty())
        assertEquals(0, count("CANCELED"))
    }

    private fun prepareRefund() {
        val preparedPayment = prepareReservationPaymentTxUseCase.prepare(
            ApproveReservationPaymentCommand(reservationId, 1, attemptNumber, "PK-TEST"),
        )
        expire()
        recordPaymentSuccessUseCase.record(
            RecordPaymentSuccessCommand("demo-pg", "PK-TEST", reservationId, 15000, "KRW", preparedPayment.transaction.pgOrderNumber),
        )
    }

    private fun makeRefundDue() {
        jdbcTemplate.update("UPDATE reservation_refund_jobs SET next_attempt_at = now() - interval '1 second'")
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

    private fun refundJobStatus() = jdbcTemplate.queryForObject("SELECT status FROM reservation_refund_jobs", String::class.java)

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
