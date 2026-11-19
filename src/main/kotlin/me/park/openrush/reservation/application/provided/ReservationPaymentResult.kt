package me.park.openrush.reservation.application.provided

import me.park.openrush.reservation.domain.PaymentStatus
import java.util.*

data class ReservationPaymentResult(
    val reservationId: Long,
    val attemptNumber: UUID,
    // null이면 PG 승인 여부를 아직 확정할 수 없다.
    val record: ReservationPaymentApprovalResult? = null,
    val refund: ReservationPaymentRefundResult? = null,
) {
    val status: ReservationPaymentResultStatus
        get() = if (refund?.cancellationId != null) {
            ReservationPaymentResultStatus.REFUNDED
        } else {
            when (record?.status) {
                null -> ReservationPaymentResultStatus.PENDING
                PaymentStatus.RESERVATION_CONFIRMED -> ReservationPaymentResultStatus.RESERVATION_CONFIRMED
                PaymentStatus.REFUND_REQUIRED -> ReservationPaymentResultStatus.REFUND_REQUIRED
            }
        }
}
