package me.park.openrush.reservation.application.provided

import java.util.*

data class RefundReservationPaymentCommand(
    val reservationId: Long,
    val userId: Long,
    val attemptNumber: UUID,
)
