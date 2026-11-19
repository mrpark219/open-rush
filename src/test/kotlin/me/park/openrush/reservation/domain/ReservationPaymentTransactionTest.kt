package me.park.openrush.reservation.domain

import org.junit.jupiter.api.Test
import java.util.UUID
import kotlin.test.assertFailsWith

class ReservationPaymentTransactionTest {
    private fun approved() = ReservationPaymentTransaction.approved(
        reservationId = 1,
        provider = "demo-pg",
        paymentId = "PK-1",
        amount = 15000,
        currency = "KRW",
        pgOrderNumber = null,
        status = PaymentStatus.RESERVATION_CONFIRMED,
        refundReason = null,
        preparation = null,
    )

    @Test
    fun `취소 준비는 양수이며 승인 금액 이하인 금액만 허용한다`() {
        listOf(-1L, 0L, 15001L).forEach { amount ->
            assertFailsWith<IllegalArgumentException> {
                approved().prepareCancellation(UUID.randomUUID(), amount, "취소")
            }
        }
    }

    @Test
    fun `취소 준비에는 비어 있지 않은 사유가 필요하다`() {
        listOf("", " ", "가".repeat(256)).forEach { reason ->
            assertFailsWith<IllegalArgumentException> {
                approved().prepareCancellation(UUID.randomUUID(), 5000, reason)
            }
        }
    }

    @Test
    fun `승인 전에는 취소를 준비할 수 없고 취소 준비 전에는 완료를 만들 수 없다`() {
        val preparation = ReservationPaymentTransaction.prepare(1, UUID.randomUUID(), 15000, "PK-1")
        assertFailsWith<IllegalArgumentException> {
            preparation.prepareCancellation(UUID.randomUUID(), 5000, "취소")
        }
        assertFailsWith<IllegalArgumentException> { approved().canceled("cancel-1") }
    }
}
