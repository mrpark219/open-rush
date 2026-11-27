package me.park.openrush.reservation.application

import java.util.UUID

data class ReservationRefundClaim(
    val jobId: Long,
    val approvedPaymentTransactionId: Long,
    val token: UUID,
)
