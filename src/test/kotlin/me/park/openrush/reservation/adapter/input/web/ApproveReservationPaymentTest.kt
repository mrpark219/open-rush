package me.park.openrush.reservation.adapter.input.web

import jakarta.servlet.ServletException
import me.park.openrush.reservation.application.ReservationApprovalRecoveryTransactions
import me.park.openrush.reservation.application.provided.ProcessReservationRefundJobsUseCase
import me.park.openrush.reservation.application.provided.RecoverReservationPaymentApprovalsUseCase
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
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate
import java.util.*
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

@DatabaseIntegrationTest
class ApproveReservationPaymentTest {
    @Autowired
    private lateinit var reservationPaymentController: ReservationPaymentController

    @Autowired
    private lateinit var globalExceptionHandler: GlobalExceptionHandler

    @Autowired
    private lateinit var createReservationUseCase: CreateReservationUseCase

    @Autowired
    private lateinit var jdbcTemplate: JdbcTemplate

    @Autowired
    private lateinit var transactionManager: PlatformTransactionManager

    @Autowired
    private lateinit var recoverReservationPaymentApprovalsUseCase: RecoverReservationPaymentApprovalsUseCase

    @Autowired
    private lateinit var processReservationRefundJobsUseCase: ProcessReservationRefundJobsUseCase

    @Autowired
    private lateinit var reservationApprovalRecoveryTransactions: ReservationApprovalRecoveryTransactions

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
    fun `준비를 커밋하고 잠금을 해제한 뒤 PG 승인과 예매 확정을 처리한다`() {
        var committedAndUnlocked = false
        normalResponses(beforeApproval = {
            TransactionTemplate(transactionManager).executeWithoutResult {
                jdbcTemplate.queryForObject(
                    "SELECT id FROM reservations WHERE id = ? FOR UPDATE NOWAIT",
                    Long::class.java,
                    reservationId
                )
                assertEquals(1, count("APPROVAL_PREPARED"))
                assertEquals("PENDING", recoveryStatus())
                committedAndUnlocked = true
            }
        })
        confirm().andExpect(status().isOk)
            .andExpect(jsonPath("$.status").value("RESERVATION_CONFIRMED"))
            .andExpect(jsonPath("$.paymentKey").value("PK-TEST"))
        assertTrue(committedAndUnlocked)
        assertEquals("COMPLETED", recoveryStatus())
        assertEquals("CONFIRMED", reservationStatus())
        assertEquals(1, count("APPROVED"))
        assertEquals(listOf("GET", "POST"), demoPgStubServer.requests.map { it.method })
        assertEquals(
            "PK-TEST",
            jdbcTemplate.queryForObject(
                "SELECT requested_payment_key FROM reservation_payment_tx WHERE type = 'APPROVAL_PREPARED'",
                String::class.java
            )
        )
    }

    @Test
    fun `완료된 같은 요청은 PG 재호출 없이 기존 결과를 반환한다`() {
        normalResponses()
        confirm().andExpect(status().isOk)
        val requestCount = demoPgStubServer.requests.size
        confirm().andExpect(status().isOk).andExpect(jsonPath("$.status").value("RESERVATION_CONFIRMED"))
        assertEquals(requestCount, demoPgStubServer.requests.size)
        assertEquals(1, count("APPROVED"))
        confirm("PK-CHANGED").andExpect(status().isConflict)
        assertEquals(requestCount, demoPgStubServer.requests.size)
        confirm(userId = 2).andExpect(status().isConflict)
        assertEquals(requestCount, demoPgStubServer.requests.size)
    }

    @Test
    fun `승인 응답이 유실되어도 주문 조회로 승인 결과를 저장한다`() {
        val approved = AtomicBoolean(false)
        demoPgStubServer.respond = { exchange ->
            if (exchange.requestMethod == "POST") {
                approved.set(true)
                exchange.close()
            } else if (!approved.get()) exchange.reply(404, "{}") else exchange.reply(200, done())
        }
        confirm().andExpect(status().isOk).andExpect(jsonPath("$.status").value("RESERVATION_CONFIRMED"))
        assertEquals(listOf("GET", "POST", "GET"), demoPgStubServer.requests.map { it.method })
        assertEquals(1, count("APPROVED"))
    }

    @Test
    fun `승인 읽기 타임아웃도 주문 조회로 복구하며 자동 승인 재시도는 하지 않는다`() {
        val release = CountDownLatch(1)
        val approved = CountDownLatch(1)
        demoPgStubServer.respond = { exchange ->
            if (exchange.requestMethod == "POST") {
                approved.countDown()
                release.await(5, TimeUnit.SECONDS)
                exchange.close()
            } else if (approved.count > 0) exchange.reply(404, "{}") else exchange.reply(200, done())
        }
        try {
            confirm().andExpect(status().isOk).andExpect(jsonPath("$.status").value("RESERVATION_CONFIRMED"))
            assertEquals(listOf("GET", "POST", "GET"), demoPgStubServer.requests.map { it.method })
        } finally {
            release.countDown()
        }
    }

    @Test
    fun `같은 시도의 동시 승인도 동일한 멱등 키를 사용하고 결과는 한 행만 저장한다`() {
        val callers = Executors.newFixedThreadPool(2)
        val posts = CountDownLatch(2)
        normalResponses(beforeApproval = {
            posts.countDown()
            assertTrue(posts.await(5, TimeUnit.SECONDS))
        })
        try {
            val results = List(2) { callers.submit<Int> { confirm().andReturn().response.status } }
            assertEquals(listOf(200, 200), results.map { it.get(10, TimeUnit.SECONDS) })
            assertEquals(1, count("APPROVED"))
            assertEquals(1, count("APPROVAL_PREPARED"))
            val approvalRequests = demoPgStubServer.requests.filter { it.method == "POST" }
            assertTrue(approvalRequests.isNotEmpty())
            assertTrue(approvalRequests.all { it.hasSameContentAs(approvalRequests.first()) })
        } finally {
            callers.shutdownNow()
        }
    }

    @Test
    fun `승인과 후속 조회가 실패하면 확인 대기하고 재요청에서 같은 주문과 키를 사용한다`() {
        val getCount = AtomicInteger()
        demoPgStubServer.respond = { exchange ->
            if (exchange.requestMethod == "GET" && getCount.incrementAndGet() == 1) exchange.reply(404, "{}")
            else exchange.reply(503, "{}")
        }
        confirm().andExpect(status().isAccepted).andExpect(jsonPath("$.status").value("PENDING"))
        assertEquals("HELD", reservationStatus())
        assertEquals(0, count("APPROVED"))
        val firstApproval = demoPgStubServer.requests.single { it.method == "POST" }
        normalResponses()
        confirm().andExpect(status().isOk)
        assertTrue(firstApproval.hasSameContentAs(demoPgStubServer.requests.last { it.method == "POST" }))
        assertEquals(1, count("APPROVAL_PREPARED"))
    }

    @Test
    fun `최초 조회 장애가 있으면 중복 승인을 시도하지 않고 확인 대기한다`() {
        demoPgStubServer.respond = { it.reply(503, "{}") }
        confirm().andExpect(status().isAccepted)
        assertEquals(listOf("GET"), demoPgStubServer.requests.map { it.method })
    }

    @Test
    fun `PG 승인 후 DB 저장이 롤백되어도 스케줄러의 주문 조회로 복구한다`() {
        val approved = AtomicBoolean(false)
        demoPgStubServer.respond = {
            if (it.requestMethod == "POST") {
                approved.set(true); it.reply(200, done())
            } else if (approved.get()) it.reply(200, done()) else it.reply(404, "{}")
        }
        jdbcTemplate.execute(
            """
            CREATE FUNCTION reject_test_approval() RETURNS trigger LANGUAGE plpgsql AS $$
            BEGIN
                IF NEW.type = 'APPROVED' THEN
                    RAISE EXCEPTION 'forced failure' USING ERRCODE = '23514';
                END IF;
                RETURN NEW;
            END $$
        """.trimIndent()
        )
        jdbcTemplate.execute("CREATE TRIGGER reject_test_approval BEFORE INSERT ON reservation_payment_tx FOR EACH ROW EXECUTE FUNCTION reject_test_approval()")
        try {
            assertFailsWith<ServletException> { confirm() }
            assertEquals("HELD", reservationStatus())
            assertEquals("PENDING", recoveryStatus())
            assertEquals(1, count("APPROVAL_PREPARED"))
            assertEquals(0, count("APPROVED"))
        } finally {
            jdbcTemplate.execute("DROP TRIGGER reject_test_approval ON reservation_payment_tx")
            jdbcTemplate.execute("DROP FUNCTION reject_test_approval()")
        }

        makeRecoveryDue()
        recoverReservationPaymentApprovalsUseCase.recoverDueApprovals()
        assertEquals("CONFIRMED", reservationStatus())
        assertEquals("COMPLETED", recoveryStatus())
        assertEquals(listOf("GET", "POST", "GET"), demoPgStubServer.requests.map { it.method })
        assertEquals(1, count("APPROVED"))
        confirm().andExpect(status().isOk).andExpect(jsonPath("$.status").value("RESERVATION_CONFIRMED"))
        assertEquals(3, demoPgStubServer.requests.size)
    }

    @Test
    fun `승인 응답이 유실되고 예약이 만료되면 자동 복구 뒤 환불 작업으로 처리한다`() {
        val approved = AtomicBoolean(false)
        demoPgStubServer.respond = { exchange ->
            if (exchange.requestMethod == "POST") {
                approved.set(true)
                exchange.close()
            } else if (approved.get()) {
                exchange.reply(503, "{}")
            } else {
                exchange.reply(404, "{}")
            }
        }
        confirm().andExpect(status().isAccepted)
        assertTrue(approved.get())
        expire()

        demoPgStubServer.respond = { exchange ->
            assertEquals("GET", exchange.requestMethod)
            assertEquals("/v1/payments/orders/$orderId", exchange.requestURI.path)
            exchange.reply(200, done())
        }
        demoPgStubServer.requests.clear()
        makeRecoveryDue()
        recoverReservationPaymentApprovalsUseCase.recoverDueApprovals()

        assertEquals("COMPLETED", recoveryStatus())
        assertEquals("REFUND_REQUIRED", jdbcTemplate.queryForObject(
            "SELECT status FROM reservation_payment_tx WHERE type = 'APPROVED'", String::class.java,
        ))
        assertEquals("PENDING", jdbcTemplate.queryForObject("SELECT status FROM reservation_refund_jobs", String::class.java))
        assertEquals(listOf("GET"), demoPgStubServer.requests.map { it.method })

        demoPgStubServer.respond = { exchange ->
            assertEquals("/v1/payments/PK-TEST/cancel", exchange.requestURI.path)
            exchange.reply(200, """
                {"paymentKey":"PK-TEST","orderId":"$orderId","currency":"KRW","totalAmount":15000,
                 "balanceAmount":0,"status":"CANCELED","cancels":[{"transactionKey":"CANCEL-RECOVERY",
                 "cancelAmount":15000,"cancelReason":"RESERVATION_UNAVAILABLE","cancelStatus":"DONE"}]}
            """.trimIndent())
        }
        processReservationRefundJobsUseCase.processDueRefunds()
        assertEquals("COMPLETED", jdbcTemplate.queryForObject("SELECT status FROM reservation_refund_jobs", String::class.java))
        assertEquals(1, count("CANCELED"))
    }

    @Test
    fun `404는 유예기간 중 재조회하고 유예기간 뒤에도 PG 장애와 구분한다`() {
        demoPgStubServer.respond = { it.reply(503, "{}") }
        confirm().andExpect(status().isAccepted)
        demoPgStubServer.requests.clear()

        demoPgStubServer.respond = { it.reply(404, "{}") }
        makeRecoveryDue()
        recoverReservationPaymentApprovalsUseCase.recoverDueApprovals()
        assertEquals("PENDING", recoveryStatus())
        recoverReservationPaymentApprovalsUseCase.recoverDueApprovals()
        assertEquals(1, demoPgStubServer.requests.size)

        jdbcTemplate.update("UPDATE reservation_approval_recovery_jobs SET lookup_until = now() - interval '1 second'")
        demoPgStubServer.respond = { it.reply(503, "{}") }
        makeRecoveryDue()
        recoverReservationPaymentApprovalsUseCase.recoverDueApprovals()
        assertEquals("PENDING", recoveryStatus())
        assertEquals("PG_UNAVAILABLE", jdbcTemplate.queryForObject(
            "SELECT last_failure FROM reservation_approval_recovery_jobs", String::class.java,
        ))

        demoPgStubServer.respond = { it.reply(404, "{}") }
        makeRecoveryDue()
        recoverReservationPaymentApprovalsUseCase.recoverDueApprovals()
        assertEquals("NOT_FOUND", recoveryStatus())
        recoverReservationPaymentApprovalsUseCase.recoverDueApprovals()
        assertEquals(listOf("GET", "GET", "GET"), demoPgStubServer.requests.map { it.method })
        assertEquals(0, count("APPROVED"))
    }

    @Test
    fun `다른 스케줄러는 점유 만료 뒤 조회를 이어받고 정상 승인 뒤 늦게 온 404는 무시한다`() {
        demoPgStubServer.respond = { it.reply(503, "{}") }
        confirm().andExpect(status().isAccepted)
        makeRecoveryDue()

        val firstClaim = assertNotNull(reservationApprovalRecoveryTransactions.claimNext())
        assertNull(reservationApprovalRecoveryTransactions.claimNext())
        jdbcTemplate.update("UPDATE reservation_approval_recovery_jobs SET lease_expires_at = now() - interval '1 second'")
        val secondClaim = assertNotNull(reservationApprovalRecoveryTransactions.claimNext())
        assertEquals(firstClaim.paymentPreparation.id, secondClaim.paymentPreparation.id)
        assertTrue(firstClaim.token != secondClaim.token)
        reservationApprovalRecoveryTransactions.recordNotFound(firstClaim)
        assertNull(reservationApprovalRecoveryTransactions.claimNext())

        demoPgStubServer.respond = { it.reply(200, done()) }
        confirm().andExpect(status().isOk)
        reservationApprovalRecoveryTransactions.recordNotFound(secondClaim)
        assertEquals("COMPLETED", recoveryStatus())
        assertEquals(1, count("APPROVED"))
    }

    @Test
    fun `PG 카드 거절은 422이고 예매 확정과 승인 이력이 없다`() {
        demoPgStubServer.respond = {
            if (it.requestMethod == "GET") it.reply(404, "{}") else it.reply(
                403,
                """{"code":"REJECT_CARD_PAYMENT"}"""
            )
        }
        confirm().andExpect(status().isUnprocessableContent).andExpect(jsonPath("$.code").value("PAYMENT_REJECTED"))
        assertEquals("HELD", reservationStatus())
        assertEquals(0, count("APPROVED"))
    }

    @Test
    fun `PG 응답 주문이나 결제 키 또는 상태가 다르면 확정하지 않는다`() {
        listOf<() -> String>(
            { done(order = "OTHER-ORDER") },
            { done(key = "OTHER-KEY") },
            { done(state = "CANCELED") }).forEach { response ->
            val posted = AtomicBoolean(false)
            demoPgStubServer.respond = {
                if (it.requestMethod == "POST") {
                    posted.set(true); it.reply(200, response())
                } else if (!posted.get()) it.reply(404, "{}") else it.reply(200, response())
            }
            confirm().andExpect(status().isAccepted)
            assertEquals(0, count("APPROVED"))
        }
        assertEquals("HELD", reservationStatus())
    }

    @Test
    fun `PG 승인 금액과 통화 불일치는 승인 사실과 환불 필요를 저장한다`() {
        normalResponses(response = { done(amount = 10000, currency = "USD") })
        confirm().andExpect(status().isOk)
            .andExpect(jsonPath("$.status").value("REFUND_REQUIRED"))
            .andExpect(jsonPath("$.refundReason").value("PAYMENT_MISMATCH"))
        assertEquals(1, count("APPROVED"))
        assertEquals("HELD", reservationStatus())
    }

    @Test
    fun `결제 도중 예매가 만료되면 승인 사실과 환불 필요를 저장한다`() {
        normalResponses(beforeApproval = { expire() })
        confirm().andExpect(status().isOk)
            .andExpect(jsonPath("$.status").value("REFUND_REQUIRED"))
            .andExpect(jsonPath("$.refundReason").value("RESERVATION_UNAVAILABLE"))
        assertEquals(1, count("APPROVED"))
    }

    @Test
    fun `확인 대기 후 만료되어도 기존 승인 조회와 기록은 가능하다`() {
        demoPgStubServer.respond = { it.reply(503, "{}") }
        confirm().andExpect(status().isAccepted)
        expire()
        demoPgStubServer.respond = { it.reply(200, done()) }
        confirm().andExpect(status().isOk).andExpect(jsonPath("$.status").value("REFUND_REQUIRED"))
        assertEquals(listOf("GET", "GET", "POST"), demoPgStubServer.requests.map { it.method })
    }

    @Test
    fun `확인 대기 후 만료된 주문이 PG에 없으면 새 승인은 하지 않는다`() {
        demoPgStubServer.respond = { it.reply(503, "{}") }
        confirm().andExpect(status().isAccepted)
        expire()
        demoPgStubServer.respond = { it.reply(404, "{}") }
        confirm().andExpect(status().isAccepted)
        assertEquals(listOf("GET", "GET"), demoPgStubServer.requests.map { it.method })
        assertEquals(0, count("APPROVED"))
    }

    @Test
    fun `만료된 예매의 새로운 시도와 타인의 예매는 PG를 호출하지 않는다`() {
        confirm(userId = 2).andExpect(status().isConflict)
        expire()
        confirm().andExpect(status().isConflict)
        assertTrue(demoPgStubServer.requests.isEmpty())
        assertEquals(0, count("APPROVAL_PREPARED"))
    }

    @Test
    fun `잘못된 입력은 PG 호출 전에 거절한다`() {
        listOf(
            """{"userId":0,"attemptNumber":"$attemptNumber","paymentKey":"PK"}""",
            """{"userId":1,"attemptNumber":"invalid","paymentKey":"PK"}""",
            """{"userId":1,"attemptNumber":"$attemptNumber","paymentKey":" "}""",
            """{"userId":1,"attemptNumber":"$attemptNumber","paymentKey":"${"X".repeat(201)}"}""",
            "{}"
        ).forEach { body ->
            mockMvc.perform(
                post("/reservations/$reservationId/confirm").contentType(MediaType.APPLICATION_JSON)
                    .content(body)
            )
                .andExpect(status().isBadRequest)
        }
        assertTrue(demoPgStubServer.requests.isEmpty())
    }

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

    private fun makeRecoveryDue() {
        jdbcTemplate.update("UPDATE reservation_approval_recovery_jobs SET next_lookup_at = now() - interval '1 second'")
    }

    private fun recoveryStatus() =
        jdbcTemplate.queryForObject("SELECT status FROM reservation_approval_recovery_jobs", String::class.java)

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
