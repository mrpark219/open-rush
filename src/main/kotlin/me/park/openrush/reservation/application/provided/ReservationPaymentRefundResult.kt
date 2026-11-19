package me.park.openrush.reservation.application.provided

import java.util.*

data class ReservationPaymentRefundResult(
    val reservationId: Long,
    val cancellationNumber: UUID,
    val cancellationId: String?,
) {
    val status: ReservationPaymentResultStatus
        get() = if (cancellationId == null) ReservationPaymentResultStatus.PENDING else ReservationPaymentResultStatus.REFUNDED
}
