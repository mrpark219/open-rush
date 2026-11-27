package me.park.openrush.reservation.adapter.input.scheduling

import me.park.openrush.reservation.application.CancelReservationTransactions
import me.park.openrush.reservation.application.provided.ApproveReservationPaymentCommand
import me.park.openrush.reservation.application.provided.CancelReservationCommand
import me.park.openrush.reservation.application.provided.CreateReservationCommand
import me.park.openrush.reservation.application.provided.CreateReservationUseCase
import me.park.openrush.reservation.application.provided.PrepareReservationPaymentTxUseCase
import me.park.openrush.reservation.application.provided.RecordPaymentSuccessCommand
import me.park.openrush.reservation.application.provided.RecordPaymentSuccessUseCase
import me.park.openrush.support.database.DatabaseIntegrationTest
import me.park.openrush.support.fixture.ConcertFixture
import me.park.openrush.support.fixture.InventoryFixture
import me.park.openrush.support.pg.DemoPgStubServer
import me.park.openrush.support.pg.reply
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.annotation.DirtiesContext
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import java.time.Duration
import java.util.UUID
import kotlin.test.assertEquals

@DatabaseIntegrationTest(properties = [
    "reservation.refund.enabled=true",
    "reservation.refund.fixed-delay=100ms",
    "reservation.refund.initial-delay=100ms",
])
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class ReservationRefundSchedulerTest {
    @Autowired
    private lateinit var jdbcTemplate: JdbcTemplate

    @Autowired
    private lateinit var createReservationUseCase: CreateReservationUseCase

    @Autowired
    private lateinit var prepareReservationPaymentTxUseCase: PrepareReservationPaymentTxUseCase

    @Autowired
    private lateinit var recordPaymentSuccessUseCase: RecordPaymentSuccessUseCase

    @Autowired
    private lateinit var cancelReservationTransactions: CancelReservationTransactions

    @Test
    fun `예약 취소 직후 프로세스가 멈춰도 스케줄러가 저장된 환불 작업을 처리한다`() {
        val concertFixture = ConcertFixture(jdbcTemplate)
        val performanceId = concertFixture.createPerformance(concertFixture.createConcert())
        val seatId = InventoryFixture(jdbcTemplate).createSeat(performanceId)
        val userId = 1L
        val paymentKey = "PK-SCHEDULED-REFUND"
        val reservationId = createReservationUseCase.create(
            CreateReservationCommand(userId, performanceId, listOf(seatId)),
        ).reservationId
        val preparation = prepareReservationPaymentTxUseCase.prepare(
            ApproveReservationPaymentCommand(reservationId, userId, UUID.randomUUID(), paymentKey),
        ).transaction
        recordPaymentSuccessUseCase.record(
            RecordPaymentSuccessCommand("demo-pg", paymentKey, reservationId, 15000, "KRW", preparation.pgOrderNumber),
        )

        demoPgStubServer.respond = { exchange ->
            exchange.reply(200, """
                {"paymentKey":"$paymentKey","orderId":"${preparation.pgOrderNumber}","currency":"KRW",
                 "totalAmount":15000,"balanceAmount":0,"status":"CANCELED",
                 "cancels":[{"transactionKey":"CANCEL-SCHEDULED","cancelAmount":15000,
                             "cancelReason":"USER_REQUEST","cancelStatus":"DONE"}]}
            """.trimIndent())
        }

        // 즉시 PG 호출을 시작하기 전에 요청 프로세스가 종료된 상태를 만든다.
        cancelReservationTransactions.cancel(CancelReservationCommand(reservationId, userId, "USER_REQUEST"))

        val deadline = System.nanoTime() + Duration.ofSeconds(8).toNanos()
        var jobStatus: String?
        do {
            jobStatus = jdbcTemplate.queryForObject("SELECT status FROM reservation_refund_jobs", String::class.java)
            if (jobStatus == "COMPLETED") {
                break
            }
            Thread.sleep(25)
        } while (System.nanoTime() < deadline)

        assertEquals("COMPLETED", jobStatus)
        val request = demoPgStubServer.requests.single()
        assertEquals("/v1/payments/$paymentKey/cancel", request.path)
        assertEquals("CANCEL-SCHEDULED", jdbcTemplate.queryForObject(
            "SELECT cancellation_id FROM reservation_payment_tx WHERE type = 'CANCELED'",
            String::class.java,
        ))
    }

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
