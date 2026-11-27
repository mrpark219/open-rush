package me.park.openrush.reservation.application

import me.park.openrush.reservation.domain.ReservationPaymentTransaction

data class PreparedReservationRefund(
    val approvedPaymentTransactionId: Long,
    val cancellationPreparation: ReservationPaymentTransaction,
)
