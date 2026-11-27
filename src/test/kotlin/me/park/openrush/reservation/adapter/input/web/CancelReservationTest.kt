package me.park.openrush.reservation.adapter.input.web

import jakarta.servlet.ServletException
import me.park.openrush.support.database.DatabaseIntegrationTest
import me.park.openrush.support.fixture.ConcertFixture
import me.park.openrush.support.fixture.InventoryFixture
import me.park.openrush.support.pg.DemoPgStubServer
import me.park.openrush.support.pg.reply
import me.park.openrush.reservation.application.provided.CreateReservationCommand
import me.park.openrush.reservation.application.provided.CreateReservationUseCase
import me.park.openrush.reservation.application.provided.ProcessReservationRefundJobsUseCase
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
class CancelReservationTest {
    @Autowired
    private lateinit var reservationPaymentController: ReservationPaymentController

    @Autowired
    private lateinit var reservationController: ReservationController

    @Autowired
    private lateinit var confirmReservationUseCase: me.park.openrush.reservation.application.provided.ConfirmReservationUseCase

    @Autowired
    private lateinit var processReservationRefundJobsUseCase: ProcessReservationRefundJobsUseCase

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
        mockMvc = MockMvcBuilders.standaloneSetup(reservationPaymentController, reservationController).setControllerAdvice(globalExceptionHandler).build()
        val concertFixture = ConcertFixture(jdbcTemplate)
        val concertId = concertFixture.createConcert()
        val performanceId = concertFixture.createPerformance(concertId)
        val seatId = InventoryFixture(jdbcTemplate).createSeat(performanceId)
        reservationId = createReservationUseCase.create(CreateReservationCommand(1L, performanceId, listOf(seatId))).reservationId
        attemptNumber = UUID.randomUUID()
        normalResponses()
        confirm().andExpect(status().isOk)
        demoPgStubServer.requests.clear()
    }

    @Test
    fun `예약 취소와 자원 반환을 먼저 커밋하고 환불하며 재요청은 중복 반환하지 않는다`() {
        demoPgStubServer.respond = {
            assertEquals("CANCELLED", reservationStatus())
            assertEquals("AVAILABLE", seatStatus())
            assertEquals(0, allocatedQuantity())
            assertEquals(1, count("CANCEL_PREPARED"))
            assertEquals("PENDING", refundJobStatus())
            it.reply(200, canceled())
        }
        cancel().andExpect(status().isOk)
            .andExpect(jsonPath("$.status").value("CANCELLED"))
            .andExpect(jsonPath("$.refundStatus").value("REFUNDED"))
        assertEquals(1, count("CANCELED"))
        assertEquals("COMPLETED", refundJobStatus())
        assertTrue(jdbcTemplate.queryForObject("SELECT confirmed_at IS NOT NULL FROM reservations WHERE id = ?", Boolean::class.java, reservationId)!!)
        val performanceId = jdbcTemplate.queryForObject("SELECT performance_id FROM reservations WHERE id = ?", Long::class.java, reservationId)!!
        val seatId = jdbcTemplate.queryForObject("SELECT id FROM performance_seats", Long::class.java)!!
        val newReservationId = createReservationUseCase.create(CreateReservationCommand(1L, performanceId, listOf(seatId))).reservationId
        cancel().andExpect(status().isOk)
        assertEquals(1, demoPgStubServer.requests.size)
        assertEquals("HELD", seatStatus())
        assertEquals(newReservationId, jdbcTemplate.queryForObject("SELECT reservation_id FROM performance_seats", Long::class.java))
        assertEquals(1, allocatedQuantity())
        assertEquals(1, count("CANCEL_PREPARED"))
    }

    @Test
    fun `PG 장애에도 취소와 반환은 유지하고 스케줄러가 환불만 재시도한다`() {
        demoPgStubServer.respond = { it.reply(503, "{}") }
        cancel().andExpect(status().isOk).andExpect(jsonPath("$.refundStatus").value("PENDING"))
        assertEquals("CANCELLED", reservationStatus())
        assertEquals("AVAILABLE", seatStatus())
        assertEquals(0, allocatedQuantity())
        assertEquals(0, count("CANCELED"))
        assertEquals("PENDING", refundJobStatus())
        val refundJobId = jdbcTemplate.queryForObject("SELECT id FROM reservation_refund_jobs", Long::class.java)
        val firstRequest = demoPgStubServer.requests.single()
        demoPgStubServer.respond = { it.reply(200, canceled()) }
        processReservationRefundJobsUseCase.processDueRefunds()
        cancel().andExpect(status().isOk).andExpect(jsonPath("$.refundStatus").value("PENDING"))
        assertEquals(1, demoPgStubServer.requests.size)

        makeRefundDue()
        processReservationRefundJobsUseCase.processDueRefunds()
        cancel().andExpect(status().isOk).andExpect(jsonPath("$.refundStatus").value("REFUNDED"))
        assertEquals(2, demoPgStubServer.requests.size)
        assertTrue(firstRequest.hasSameContentAs(demoPgStubServer.requests.last()))
        assertEquals(1, count("CANCEL_PREPARED"))
        assertEquals(1, count("CANCELED"))
        assertEquals("COMPLETED", refundJobStatus())
        assertEquals(refundJobId, jdbcTemplate.queryForObject("SELECT id FROM reservation_refund_jobs", Long::class.java))
    }

    @Test
    fun `PG가 취소를 거절해도 예약은 취소 상태로 남는다`() {
        demoPgStubServer.respond = { it.reply(400, "{}") }
        cancel().andExpect(status().isOk).andExpect(jsonPath("$.refundStatus").value("PENDING"))
        assertEquals("CANCELLED", reservationStatus())
        assertEquals(0, allocatedQuantity())
        assertEquals(0, count("CANCELED"))
        assertEquals("NEEDS_REVIEW", refundJobStatus())
        makeRefundDue()
        processReservationRefundJobsUseCase.processDueRefunds()
        assertEquals(1, demoPgStubServer.requests.size)
    }

    @Test
    fun `다른 사용자와 다른 취소 사유의 재요청은 거절한다`() {
        cancel(userId = 2).andExpect(status().isConflict)
        assertEquals("CONFIRMED", reservationStatus())
        assertTrue(demoPgStubServer.requests.isEmpty())
        demoPgStubServer.respond = { it.reply(200, canceled()) }
        cancel().andExpect(status().isOk)
        cancel(reason = "OTHER-REASON").andExpect(status().isConflict)
        assertEquals(1, demoPgStubServer.requests.size)
    }

    @Test
    fun `좌석 반환 실패 시 취소와 한도 반환 및 환불 준비를 모두 롤백한다`() {
        jdbcTemplate.update("UPDATE performance_seats SET status = 'AVAILABLE', reservation_id = NULL")
        assertFailsWith<ServletException> { cancel() }
        assertEquals("CONFIRMED", reservationStatus())
        assertEquals(1, allocatedQuantity())
        assertEquals(0, count("CANCEL_PREPARED"))
        assertTrue(demoPgStubServer.requests.isEmpty())
    }

    @Test
    fun `환불 완료 저장이 실패해도 예약 취소는 유지하며 재요청으로 복구한다`() {
        demoPgStubServer.respond = { it.reply(200, canceled()) }
        jdbcTemplate.execute("""
            CREATE FUNCTION reject_cancel_result() RETURNS trigger LANGUAGE plpgsql AS $$
            BEGIN
                IF NEW.type = 'CANCELED' THEN
                    RAISE EXCEPTION 'forced failure' USING ERRCODE = '23514';
                END IF;
                RETURN NEW;
            END $$
        """.trimIndent())
        jdbcTemplate.execute("CREATE TRIGGER reject_cancel_result BEFORE INSERT ON reservation_payment_tx FOR EACH ROW EXECUTE FUNCTION reject_cancel_result()")
        try {
            assertFailsWith<ServletException> { cancel() }
            assertEquals("CANCELLED", reservationStatus())
            assertEquals(0, allocatedQuantity())
            assertEquals(1, count("CANCEL_PREPARED"))
            assertEquals(0, count("CANCELED"))
        } finally {
            jdbcTemplate.execute("DROP TRIGGER reject_cancel_result ON reservation_payment_tx")
            jdbcTemplate.execute("DROP FUNCTION reject_cancel_result()")
        }
        makeRefundDue()
        cancel().andExpect(status().isOk)
        assertTrue(demoPgStubServer.requests.first().hasSameContentAs(demoPgStubServer.requests.last()))
        assertEquals(1, count("CANCELED"))
    }

    @Test
    fun `예약 취소 중 스케줄러와 재요청이 들어와도 PG 호출은 한 번만 한다`() {
        val caller = Executors.newSingleThreadExecutor()
        val posted = CountDownLatch(1)
        val release = CountDownLatch(1)
        demoPgStubServer.respond = {
            posted.countDown()
            assertTrue(release.await(5, TimeUnit.SECONDS))
            it.reply(200, canceled())
        }
        try {
            val first = caller.submit<Int> { cancel().andReturn().response.status }
            assertTrue(posted.await(5, TimeUnit.SECONDS))
            processReservationRefundJobsUseCase.processDueRefunds()
            cancel().andExpect(status().isOk).andExpect(jsonPath("$.refundStatus").value("PENDING"))
            assertEquals(1, demoPgStubServer.requests.size)

            release.countDown()
            assertEquals(200, first.get(10, TimeUnit.SECONDS))
            assertEquals(0, allocatedQuantity())
            assertEquals(1, count("CANCEL_PREPARED"))
            assertEquals(1, count("CANCELED"))
        } finally {
            release.countDown()
            caller.shutdownNow()
        }
    }

    @Test
    fun `취소한 예약의 승인 재요청은 다시 확정하지 않는다`() {
        demoPgStubServer.respond = { it.reply(200, canceled()) }
        cancel().andExpect(status().isOk)
        confirm().andExpect(status().isConflict)
        assertEquals("CANCELLED", reservationStatus())
        assertEquals(1, demoPgStubServer.requests.size)
    }

    @Test
    fun `잘못된 취소 입력은 자원 변경이나 PG 호출 전에 거절한다`() {
        cancel(reason = " ").andExpect(status().isBadRequest)
        cancel(reason = "X".repeat(256)).andExpect(status().isBadRequest)
        cancel(userId = 0).andExpect(status().isBadRequest)
        assertEquals("CONFIRMED", reservationStatus())
        assertEquals(1, allocatedQuantity())
        assertTrue(demoPgStubServer.requests.isEmpty())
    }

    @Test
    fun `선점 예약은 확정 예약 취소 엔드포인트에서 거절한다`() {
        val performanceId = jdbcTemplate.queryForObject("SELECT performance_id FROM reservations WHERE id = ?", Long::class.java, reservationId)!!
        val heldSeatId = InventoryFixture(jdbcTemplate).createSeat(performanceId, seatNumber = "A-2")
        val heldReservationId = createReservationUseCase.create(CreateReservationCommand(1L, performanceId, listOf(heldSeatId))).reservationId
        cancel(targetReservationId = heldReservationId).andExpect(status().isConflict)
        assertEquals("HELD", jdbcTemplate.queryForObject("SELECT status FROM reservations WHERE id = ?", String::class.java, heldReservationId))
        assertTrue(demoPgStubServer.requests.isEmpty())
    }

    @Test
    fun `확정된 무료 예약은 환불 없이 취소하고 자원을 반환한다`() {
        val performanceId = jdbcTemplate.queryForObject("SELECT performance_id FROM reservations WHERE id = ?", Long::class.java, reservationId)!!
        val freeSeatId = InventoryFixture(jdbcTemplate).createSeat(performanceId, seatNumber = "FREE-1", amount = 0)
        val freeReservationId = createReservationUseCase.create(CreateReservationCommand(2L, performanceId, listOf(freeSeatId))).reservationId
        confirmReservationUseCase.confirm(me.park.openrush.reservation.application.provided.ConfirmReservationCommand(2L, freeReservationId))
        cancel(userId = 2, targetReservationId = freeReservationId).andExpect(status().isOk)
            .andExpect(jsonPath("$.status").value("CANCELLED"))
            .andExpect(jsonPath("$.refundStatus").doesNotExist())
        assertEquals("AVAILABLE", jdbcTemplate.queryForObject("SELECT status FROM performance_seats WHERE id = ?", String::class.java, freeSeatId))
        assertEquals(0, jdbcTemplate.queryForObject("SELECT allocated_quantity FROM concert_purchase_quotas WHERE user_id = 2", Int::class.java))
        assertEquals(0, jdbcTemplate.queryForObject("SELECT count(*) FROM reservation_refund_jobs", Int::class.java))
        assertTrue(demoPgStubServer.requests.isEmpty())
        assertEquals(0, count("CANCEL_PREPARED"))
    }

    private fun cancel(userId: Long = 1, reason: String = "USER_REQUEST", targetReservationId: Long = reservationId) = mockMvc.perform(
        post("/reservations/$targetReservationId/cancel").contentType(MediaType.APPLICATION_JSON)
            .content("""{"userId":$userId,"cancelReason":"$reason"}"""),
    )

    private fun allocatedQuantity() = jdbcTemplate.queryForObject("SELECT allocated_quantity FROM concert_purchase_quotas", Int::class.java)
    private fun seatStatus() = jdbcTemplate.queryForObject("SELECT status FROM performance_seats", String::class.java)

    private fun canceled() =
        """{"paymentKey":"PK-TEST","orderId":"$orderId","currency":"KRW","totalAmount":15000,"balanceAmount":0,"status":"CANCELED","cancels":[{"transactionKey":"CANCEL-TEST","cancelAmount":15000,"cancelReason":"USER_REQUEST","cancelStatus":"DONE"}]}"""

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

    private fun makeRefundDue() {
        jdbcTemplate.update("UPDATE reservation_refund_jobs SET next_attempt_at = now() - interval '1 second'")
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
