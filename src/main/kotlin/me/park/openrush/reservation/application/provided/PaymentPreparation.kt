package me.park.openrush.reservation.application.provided

import java.util.UUID

data class PaymentPreparation(
    val id: Long,
    val reservationId: Long,
    val attemptNumber: UUID,
    val provider: String,
    val amount: Long,
    val pgOrderNumber: String,
    val approvalIdempotencyKey: String,
    val requestedPaymentKey: String,
)
