package me.park.openrush.reservation.application.provided

import me.park.openrush.reservation.domain.PaymentStatus
import me.park.openrush.reservation.domain.RefundReason

data class ReservationPaymentApprovalResult(
    val payment: RecordPaymentSuccessCommand,
    val status: PaymentStatus,
    val refundReason: RefundReason? = null,
) {
    init {
        require((status == PaymentStatus.REFUND_REQUIRED) == (refundReason != null))
    }
}
