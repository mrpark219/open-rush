package me.park.openrush.reservation.application.port.input

import me.park.openrush.reservation.domain.payment.ReservationPaymentTx

data class PreparedReservationPayment(
    val transaction: ReservationPaymentTx,
    val approvalAllowed: Boolean,
)
