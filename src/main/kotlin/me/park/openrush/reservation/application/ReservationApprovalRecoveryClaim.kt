package me.park.openrush.reservation.application

import me.park.openrush.reservation.application.provided.PaymentPreparation
import java.time.Instant
import java.util.UUID

data class ReservationApprovalRecoveryClaim(
    val paymentPreparation: PaymentPreparation,
    val token: UUID,
    val claimedAt: Instant,
)
