package me.park.openrush.reservation.application

import me.park.openrush.support.database.DatabaseIntegrationTest
import me.park.openrush.support.database.prepareConcurrentConnections
import me.park.openrush.support.fixture.ConcertFixture
import me.park.openrush.support.fixture.InventoryFixture
import me.park.openrush.reservation.application.provided.ReservationPaymentApprovalResult
import me.park.openrush.reservation.domain.PaymentStatus
import me.park.openrush.reservation.domain.RefundReason
import me.park.openrush.reservation.application.provided.RecordPaymentSuccessCommand
import me.park.openrush.reservation.application.provided.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.springframework.context.annotation.Primary
import org.springframework.jdbc.core.JdbcTemplate
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

@Import(RecordPaymentSuccessTest.Configuration::class)
@DatabaseIntegrationTest
class RecordPaymentSuccessTest {
    @Autowired
    private lateinit var recordPaymentSuccessUseCase: RecordPaymentSuccessUseCase
    @Autowired
    private lateinit var createReservationUseCase: CreateReservationUseCase
    @Autowired
    private lateinit var cancelReservationHoldUseCase: CancelReservationHoldUseCase
    @Autowired
    private lateinit var expireReservationHoldUseCase: ExpireReservationHoldUseCase
    @Autowired
    private lateinit var jdbcTemplate: JdbcTemplate
    private lateinit var seatIds: List<Long>
    private lateinit var recordPaymentSuccessCommand: RecordPaymentSuccessCommand

    @BeforeEach
    fun setUp() {
        val concertId = ConcertFixture(jdbcTemplate).createConcert()
        val performanceId = ConcertFixture(jdbcTemplate).createPerformance(
            concertId = concertId,
            startsAt = Instant.parse("2026-11-06T00:00:00Z"),
            bookingOpensAt = Instant.parse("2026-11-03T00:00:00Z"),
            bookingClosesAt = Instant.parse("2026-11-05T00:00:00Z"),
        )
        seatIds = (1..2).map { number ->
            InventoryFixture(jdbcTemplate).createSeat(performanceId, "A$number", amount = 50_000)
        }
        val reservation = createReservationUseCase.create(CreateReservationCommand(1, performanceId, seatIds))
        recordPaymentSuccessCommand =
            RecordPaymentSuccessCommand("test-provider", "payment-1", reservation.reservationId, reservation.totalAmount, "KRW")
    }

    @Test
    fun `결제 금액이 맞으면 예매와 좌석을 확정하고 같은 결제에는 기존 결과를 반환한다`() {
        val expected = ReservationPaymentApprovalResult(recordPaymentSuccessCommand, PaymentStatus.RESERVATION_CONFIRMED)
        assertEquals(expected, recordPaymentSuccessUseCase.record(recordPaymentSuccessCommand))
        assertEquals(expected, recordPaymentSuccessUseCase.record(recordPaymentSuccessCommand))
        assertEquals(1, count("SELECT count(*) FROM reservation_payment_tx WHERE status = 'RESERVATION_CONFIRMED'"))
        assertEquals("CONFIRMED", reservationStatus())
        assertEquals(
            2,
            count(
                "SELECT count(*) FROM performance_seats WHERE status = 'RESERVED' AND reservation_id = ?",
                recordPaymentSuccessCommand.reservationId
            )
        )
        assertEquals(2, count("SELECT allocated_quantity FROM concert_purchase_quotas"))
    }

    @Test
    fun `같은 결제 키로 내용이 다른 통지가 오면 저장된 결과를 유지한다`() {
        val saved = recordPaymentSuccessUseCase.record(recordPaymentSuccessCommand)
        assertFailsWith<IllegalArgumentException> { recordPaymentSuccessUseCase.record(recordPaymentSuccessCommand.copy(amount = recordPaymentSuccessCommand.amount + 1)) }
        assertEquals(saved, recordPaymentSuccessUseCase.record(recordPaymentSuccessCommand))
        assertEquals(1, count("SELECT count(*) FROM reservation_payment_tx"))
    }

    @Test
    fun `동일 결제의 동시 통지는 같은 결과를 반환한다`() {
        val expected = ReservationPaymentApprovalResult(recordPaymentSuccessCommand, PaymentStatus.RESERVATION_CONFIRMED)
        assertEquals(listOf(expected, expected), concurrently(recordPaymentSuccessCommand, recordPaymentSuccessCommand))
        assertEquals(1, count("SELECT count(*) FROM reservation_payment_tx"))
    }

    @Test
    fun `한 예매에 서로 다른 결제가 동시에 성공하면 한 건만 연결하고 다른 결제는 환불 대기한다`() {
        val results = concurrently(recordPaymentSuccessCommand, recordPaymentSuccessCommand.copy(paymentId = "payment-2"))
        assertEquals(1, results.count { it.status == PaymentStatus.RESERVATION_CONFIRMED })
        assertEquals(
            RefundReason.RESERVATION_UNAVAILABLE,
            results.single { it.status == PaymentStatus.REFUND_REQUIRED }.refundReason
        )
        assertEquals(1, count("SELECT count(*) FROM reservation_payment_tx WHERE status = 'REFUND_REQUIRED'"))
        assertEquals("CONFIRMED", reservationStatus())
        assertEquals(2, count("SELECT count(*) FROM performance_seats WHERE status = 'RESERVED'"))
        assertEquals(2, count("SELECT allocated_quantity FROM concert_purchase_quotas"))
    }

    @Test
    fun `금액이나 통화가 맞지 않으면 예매를 확정하지 않고 환불 대기로 기록한다`() {
        val wrongAmount = recordPaymentSuccessCommand.copy(amount = recordPaymentSuccessCommand.amount - 1)
        val wrongCurrency = recordPaymentSuccessCommand.copy(paymentId = "payment-2", currency = "USD")
        listOf(wrongAmount, wrongCurrency).forEach {
            assertEquals(
                ReservationPaymentApprovalResult(it, PaymentStatus.REFUND_REQUIRED, RefundReason.PAYMENT_MISMATCH),
                recordPaymentSuccessUseCase.record(it)
            )
        }
        assertEquals(2, count("SELECT count(*) FROM reservation_payment_tx WHERE status = 'REFUND_REQUIRED'"))
        assertEquals("HELD", reservationStatus())
        assertEquals(2, count("SELECT count(*) FROM performance_seats WHERE status = 'HELD'"))
    }

    @Test
    fun `만료 시각에 결제가 도착하면 환불 대기를 남기고 만료 작업에서 좌석과 한도를 반환한다`() {
        jdbcTemplate.update(
            "UPDATE reservations SET held_at = '2026-11-03T23:59:00Z', expires_at = '2026-11-04T00:00:00Z' WHERE id = ?",
            recordPaymentSuccessCommand.reservationId
        )

        val expected = ReservationPaymentApprovalResult(recordPaymentSuccessCommand, PaymentStatus.REFUND_REQUIRED, RefundReason.RESERVATION_UNAVAILABLE)
        assertEquals(expected, recordPaymentSuccessUseCase.record(recordPaymentSuccessCommand))
        assertEquals("HELD", reservationStatus())
        assertTrue(expireReservationHoldUseCase.expireHold(recordPaymentSuccessCommand.reservationId))
        assertEquals(expected, recordPaymentSuccessUseCase.record(recordPaymentSuccessCommand))
        assertEquals(1, count("SELECT count(*) FROM reservation_payment_tx WHERE status = 'REFUND_REQUIRED'"))
        assertEquals("EXPIRED", reservationStatus())
        assertEquals(2, count("SELECT count(*) FROM performance_seats WHERE status = 'AVAILABLE'"))
        assertEquals(0, count("SELECT allocated_quantity FROM concert_purchase_quotas"))
    }

    @Test
    fun `취소된 예매의 결제도 환불 대기로 남긴다`() {
        cancelReservationHoldUseCase.cancelHold(CancelReservationHoldCommand(1, recordPaymentSuccessCommand.reservationId))
        assertEquals(
            ReservationPaymentApprovalResult(recordPaymentSuccessCommand, PaymentStatus.REFUND_REQUIRED, RefundReason.RESERVATION_UNAVAILABLE),
            recordPaymentSuccessUseCase.record(recordPaymentSuccessCommand)
        )
        assertEquals("CANCELLED", reservationStatus())
        assertEquals(1, count("SELECT count(*) FROM reservation_payment_tx WHERE status = 'REFUND_REQUIRED'"))
        assertEquals(0, count("SELECT allocated_quantity FROM concert_purchase_quotas"))
    }

    @Test
    fun `예상하지 못한 좌석 전환 실패는 결제 기록까지 롤백해 같은 통지를 다시 처리할 수 있다`() {
        jdbcTemplate.update(
            "UPDATE performance_seats SET status = 'AVAILABLE', reservation_id = NULL WHERE id = ?",
            seatIds.last()
        )
        assertFailsWith<IllegalStateException> { recordPaymentSuccessUseCase.record(recordPaymentSuccessCommand) }
        assertEquals(0, count("SELECT count(*) FROM reservation_payment_tx"))
        assertEquals("HELD", reservationStatus())
        assertEquals(0, count("SELECT count(*) FROM performance_seats WHERE status = 'RESERVED'"))

        jdbcTemplate.update(
            "UPDATE performance_seats SET status = 'HELD', reservation_id = ? WHERE id = ?",
            recordPaymentSuccessCommand.reservationId,
            seatIds.last()
        )
        assertEquals(PaymentStatus.RESERVATION_CONFIRMED, recordPaymentSuccessUseCase.record(recordPaymentSuccessCommand).status)
        assertEquals(1, count("SELECT count(*) FROM reservation_payment_tx"))
    }

    private fun concurrently(first: RecordPaymentSuccessCommand, second: RecordPaymentSuccessCommand): List<ReservationPaymentApprovalResult> {
        checkNotNull(jdbcTemplate.dataSource).prepareConcurrentConnections(2)
        val barrier = CyclicBarrier(2)
        val executor = Executors.newFixedThreadPool(2)
        return try {
            listOf(first, second).map { input ->
                executor.submit<ReservationPaymentApprovalResult> {
                    barrier.await(5, TimeUnit.SECONDS)
                    recordPaymentSuccessUseCase.record(input)
                }
            }.map { it.get(10, TimeUnit.SECONDS) }
        } finally {
            executor.shutdownNow()
            assertTrue(executor.awaitTermination(10, TimeUnit.SECONDS))
        }
    }

    private fun reservationStatus() =
        jdbcTemplate.queryForObject("SELECT status FROM reservations WHERE id = ?", String::class.java, recordPaymentSuccessCommand.reservationId)

    private fun count(sql: String, vararg args: Any) = jdbcTemplate.queryForObject(sql, Int::class.java, *args)!!

    @TestConfiguration(proxyBeanMethods = false)
    class Configuration {
        @Bean
        @Primary
        fun paymentClock(): Clock = Clock.fixed(Instant.parse("2026-11-04T00:00:00Z"), ZoneOffset.UTC)
    }
}
