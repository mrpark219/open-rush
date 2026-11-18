package me.park.openrush.reservation.domain.payment

data class PaymentRecord(
    val payment: VerifiedPayment,
    val status: PaymentStatus,
    val refundReason: RefundReason? = null,
) {
    init {
        require((status == PaymentStatus.REFUND_REQUIRED) == (refundReason != null))
    }
}
