package me.park.openrush.reservation.application.port.input

import java.util.UUID

data class ApproveReservationPaymentCommand(
    val reservationId: Long,
    val userId: Long,
    val attemptId: UUID,
    val paymentKey: String,
) {
    init {
        require(reservationId > 0 && userId > 0)
        require(paymentKey.isNotBlank() && paymentKey.length <= 200)
    }
}
