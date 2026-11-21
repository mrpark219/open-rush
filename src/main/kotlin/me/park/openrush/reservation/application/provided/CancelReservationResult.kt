package me.park.openrush.reservation.application.provided

import me.park.openrush.reservation.domain.ReservationStatus

data class CancelReservationResult(
    val reservationId: Long,
    val status: ReservationStatus,
    val refundStatus: ReservationPaymentResultStatus?,
)
