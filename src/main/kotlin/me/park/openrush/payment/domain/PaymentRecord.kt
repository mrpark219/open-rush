package me.park.openrush.payment.domain

data class PaymentRecord(
    val payment: VerifiedPayment,
    val status: PaymentStatus,
    val refundReason: RefundReason? = null,
) {
    init {
        require((status == PaymentStatus.REFUND_REQUIRED) == (refundReason != null))
    }
}

enum class PaymentStatus { RESERVATION_CONFIRMED, REFUND_REQUIRED }

enum class RefundReason { PAYMENT_MISMATCH, RESERVATION_UNAVAILABLE }
