package me.park.openrush.payment.adapter.output.persistence

import me.park.openrush.TestcontainersConfiguration
import me.park.openrush.payment.application.port.input.RecordPaymentSuccessUseCase
import me.park.openrush.payment.domain.PaymentRecord
import me.park.openrush.payment.domain.PaymentStatus
import me.park.openrush.payment.domain.RefundReason
import me.park.openrush.payment.domain.VerifiedPayment
import me.park.openrush.reservation.application.port.input.CancelReservationHoldCommand
import me.park.openrush.reservation.application.port.input.CancelReservationHoldUseCase
import me.park.openrush.reservation.application.port.input.CreateReservationCommand
import me.park.openrush.reservation.application.port.input.CreateReservationUseCase
import me.park.openrush.reservation.application.port.input.ExpireReservationHoldUseCase
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
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

@Import(TestcontainersConfiguration::class, RecordPaymentSuccessTest.Configuration::class)
@SpringBootTest(properties = ["reservation.expiration.enabled=false"])
class RecordPaymentSuccessTest {
    @Autowired private lateinit var recordPayment: RecordPaymentSuccessUseCase
    @Autowired private lateinit var create: CreateReservationUseCase
    @Autowired private lateinit var cancel: CancelReservationHoldUseCase
    @Autowired private lateinit var expire: ExpireReservationHoldUseCase
    @Autowired private lateinit var jdbc: JdbcTemplate
    private lateinit var seats: List<Long>
    private lateinit var payment: VerifiedPayment

    @BeforeEach
    fun setUp() {
        jdbc.execute("TRUNCATE concerts CASCADE")
        val concertId = id("INSERT INTO concerts(title, description, max_tickets_per_user) VALUES ('공연', '', 4) RETURNING id")
        val performanceId = id("""INSERT INTO performances(concert_id, venue, starts_at, booking_opens_at, booking_closes_at)
            VALUES (?, '공연장', '2026-11-06T00:00:00Z', '2026-11-03T00:00:00Z', '2026-11-05T00:00:00Z') RETURNING id""", concertId)
        seats = (1..2).map { number ->
            id("""INSERT INTO performance_seats(performance_id, seat_number, price, status)
                VALUES (?, ?, 50000, 'AVAILABLE') RETURNING id""", performanceId, "A$number")
        }
        val reservation = create.create(CreateReservationCommand(1, performanceId, seats))
        payment = VerifiedPayment("test-provider", "payment-1", reservation.reservationId, reservation.totalAmount, "KRW")
    }

    @Test
    fun `결제 금액이 맞으면 예매와 좌석을 확정하고 같은 결제에는 기존 결과를 반환한다`() {
        val expected = PaymentRecord(payment, PaymentStatus.RESERVATION_CONFIRMED)
        assertEquals(expected, recordPayment.record(payment))
        assertEquals(expected, recordPayment.record(payment))
        assertEquals(1, count("SELECT count(*) FROM payment_records WHERE status = 'RESERVATION_CONFIRMED'"))
        assertEquals("CONFIRMED", reservationStatus())
        assertEquals(2, count("SELECT count(*) FROM performance_seats WHERE status = 'RESERVED' AND reservation_id = ?", payment.reservationId))
        assertEquals(2, count("SELECT allocated_quantity FROM concert_purchase_quotas"))
    }

    @Test
    fun `같은 결제 키로 내용이 다른 통지가 오면 저장된 결과를 유지한다`() {
        val saved = recordPayment.record(payment)
        assertFailsWith<IllegalArgumentException> { recordPayment.record(payment.copy(amount = payment.amount + 1)) }
        assertEquals(saved, recordPayment.record(payment))
        assertEquals(1, count("SELECT count(*) FROM payment_records"))
    }

    @Test
    fun `동일 결제의 동시 통지는 같은 결과를 반환한다`() {
        val expected = PaymentRecord(payment, PaymentStatus.RESERVATION_CONFIRMED)
        assertEquals(listOf(expected, expected), concurrently(payment, payment))
        assertEquals(1, count("SELECT count(*) FROM payment_records"))
    }

    @Test
    fun `한 예매에 서로 다른 결제가 동시에 성공하면 한 건만 연결하고 다른 결제는 환불 대기한다`() {
        val results = concurrently(payment, payment.copy(paymentId = "payment-2"))
        assertEquals(1, results.count { it.status == PaymentStatus.RESERVATION_CONFIRMED })
        assertEquals(RefundReason.RESERVATION_UNAVAILABLE, results.single { it.status == PaymentStatus.REFUND_REQUIRED }.refundReason)
        assertEquals(1, count("SELECT count(*) FROM payment_records WHERE status = 'REFUND_REQUIRED'"))
        assertEquals("CONFIRMED", reservationStatus())
        assertEquals(2, count("SELECT count(*) FROM performance_seats WHERE status = 'RESERVED'"))
        assertEquals(2, count("SELECT allocated_quantity FROM concert_purchase_quotas"))
    }

    @Test
    fun `금액이나 통화가 맞지 않으면 예매를 확정하지 않고 환불 대기로 기록한다`() {
        val wrongAmount = payment.copy(amount = payment.amount - 1)
        val wrongCurrency = payment.copy(paymentId = "payment-2", currency = "USD")
        listOf(wrongAmount, wrongCurrency).forEach {
            assertEquals(PaymentRecord(it, PaymentStatus.REFUND_REQUIRED, RefundReason.PAYMENT_MISMATCH), recordPayment.record(it))
        }
        assertEquals(2, count("SELECT count(*) FROM payment_records WHERE status = 'REFUND_REQUIRED'"))
        assertEquals("HELD", reservationStatus())
        assertEquals(2, count("SELECT count(*) FROM performance_seats WHERE status = 'HELD'"))
    }

    @Test
    fun `만료 시각에 결제가 도착하면 환불 대기를 남기고 만료 작업에서 좌석과 한도를 반환한다`() {
        jdbc.update("UPDATE reservations SET held_at = '2026-11-03T23:59:00Z', expires_at = '2026-11-04T00:00:00Z' WHERE id = ?", payment.reservationId)

        val expected = PaymentRecord(payment, PaymentStatus.REFUND_REQUIRED, RefundReason.RESERVATION_UNAVAILABLE)
        assertEquals(expected, recordPayment.record(payment))
        assertEquals("HELD", reservationStatus())
        assertTrue(expire.expireHold(payment.reservationId))
        assertEquals(expected, recordPayment.record(payment))
        assertEquals(1, count("SELECT count(*) FROM payment_records WHERE status = 'REFUND_REQUIRED'"))
        assertEquals("EXPIRED", reservationStatus())
        assertEquals(2, count("SELECT count(*) FROM performance_seats WHERE status = 'AVAILABLE'"))
        assertEquals(0, count("SELECT allocated_quantity FROM concert_purchase_quotas"))
    }

    @Test
    fun `취소된 예매의 결제도 환불 대기로 남긴다`() {
        cancel.cancelHold(CancelReservationHoldCommand(1, payment.reservationId))
        assertEquals(PaymentRecord(payment, PaymentStatus.REFUND_REQUIRED, RefundReason.RESERVATION_UNAVAILABLE), recordPayment.record(payment))
        assertEquals("CANCELLED", reservationStatus())
        assertEquals(1, count("SELECT count(*) FROM payment_records WHERE status = 'REFUND_REQUIRED'"))
        assertEquals(0, count("SELECT allocated_quantity FROM concert_purchase_quotas"))
    }

    @Test
    fun `예상하지 못한 좌석 전환 실패는 결제 기록까지 롤백해 같은 통지를 다시 처리할 수 있다`() {
        jdbc.update("UPDATE performance_seats SET status = 'AVAILABLE', reservation_id = NULL WHERE id = ?", seats.last())
        assertFailsWith<IllegalStateException> { recordPayment.record(payment) }
        assertEquals(0, count("SELECT count(*) FROM payment_records"))
        assertEquals("HELD", reservationStatus())
        assertEquals(0, count("SELECT count(*) FROM performance_seats WHERE status = 'RESERVED'"))

        jdbc.update("UPDATE performance_seats SET status = 'HELD', reservation_id = ? WHERE id = ?", payment.reservationId, seats.last())
        assertEquals(PaymentStatus.RESERVATION_CONFIRMED, recordPayment.record(payment).status)
        assertEquals(1, count("SELECT count(*) FROM payment_records"))
    }

    private fun concurrently(first: VerifiedPayment, second: VerifiedPayment): List<PaymentRecord> {
        val barrier = CyclicBarrier(2)
        val executor = Executors.newFixedThreadPool(2)
        return try {
            listOf(first, second).map { input ->
                executor.submit<PaymentRecord> {
                    barrier.await(5, TimeUnit.SECONDS)
                    recordPayment.record(input)
                }
            }.map { it.get(10, TimeUnit.SECONDS) }
        } finally {
            executor.shutdownNow()
            assertTrue(executor.awaitTermination(10, TimeUnit.SECONDS))
        }
    }

    private fun reservationStatus() = jdbc.queryForObject("SELECT status FROM reservations WHERE id = ?", String::class.java, payment.reservationId)
    private fun count(sql: String, vararg args: Any) = jdbc.queryForObject(sql, Int::class.java, *args)!!
    private fun id(sql: String, vararg args: Any) = jdbc.queryForObject(sql, Long::class.java, *args)!!

    @TestConfiguration(proxyBeanMethods = false)
    class Configuration {
        @Bean @Primary
        fun paymentClock(): Clock = Clock.fixed(Instant.parse("2026-11-04T00:00:00Z"), ZoneOffset.UTC)
    }
}
