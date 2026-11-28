package me.park.openrush.reservation.adapter.input.scheduling

import me.park.openrush.reservation.application.provided.ApproveReservationPaymentCommand
import me.park.openrush.reservation.application.provided.CreateReservationCommand
import me.park.openrush.reservation.application.provided.CreateReservationUseCase
import me.park.openrush.reservation.application.provided.PrepareReservationPaymentTxUseCase
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
    "reservation.approval-recovery.enabled=true",
    "reservation.approval-recovery.fixed-delay=100ms",
    "reservation.approval-recovery.initial-delay=100ms",
])
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class ReservationApprovalRecoverySchedulerTest {
    @Autowired
    private lateinit var jdbcTemplate: JdbcTemplate

    @Autowired
    private lateinit var createReservationUseCase: CreateReservationUseCase

    @Autowired
    private lateinit var prepareReservationPaymentTxUseCase: PrepareReservationPaymentTxUseCase

    @Test
    fun `기동한 스케줄러가 결과가 저장되지 않은 PG 승인을 조회해서 예약을 확정한다`() {
        val concertFixture = ConcertFixture(jdbcTemplate)
        val performanceId = concertFixture.createPerformance(concertFixture.createConcert())
        val seatId = InventoryFixture(jdbcTemplate).createSeat(performanceId)
        val reservationId = createReservationUseCase.create(CreateReservationCommand(1, performanceId, listOf(seatId))).reservationId
        val preparation = prepareReservationPaymentTxUseCase.prepare(
            ApproveReservationPaymentCommand(reservationId, 1, UUID.randomUUID(), "PK-APPROVAL-RECOVERY"),
        ).transaction

        demoPgStubServer.respond = { exchange ->
            exchange.reply(200, """
                {"paymentKey":"${preparation.requestedPaymentKey}","orderId":"${preparation.pgOrderNumber}",
                 "currency":"KRW","totalAmount":15000,"balanceAmount":15000,"status":"DONE"}
            """.trimIndent())
        }
        jdbcTemplate.update("UPDATE reservation_approval_recovery_jobs SET next_lookup_at = now()")

        val deadline = System.nanoTime() + Duration.ofSeconds(8).toNanos()
        var recoveryStatus: String?
        do {
            recoveryStatus = jdbcTemplate.queryForObject("SELECT status FROM reservation_approval_recovery_jobs", String::class.java)
            if (recoveryStatus == "COMPLETED") {
                break
            }
            Thread.sleep(25)
        } while (System.nanoTime() < deadline)

        assertEquals("COMPLETED", recoveryStatus)
        assertEquals("CONFIRMED", jdbcTemplate.queryForObject("SELECT status FROM reservations WHERE id = ?", String::class.java, reservationId))
        val request = demoPgStubServer.requests.single()
        assertEquals("GET", request.method)
        assertEquals("/v1/payments/orders/${preparation.pgOrderNumber}", request.path)
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
