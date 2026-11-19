package me.park.openrush.reservation.application.provided

import java.util.*

data class ApproveReservationPaymentCommand(
    val reservationId: Long,
    val userId: Long,
    val attemptNumber: UUID,
    val paymentKey: String,
) {
    init {
        require(reservationId > 0 && userId > 0)
        require(paymentKey.isNotBlank() && paymentKey.length <= 200)
    }
}
