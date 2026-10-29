package me.park.openrush.reservation.application.port.input

import me.park.openrush.reservation.domain.ReservationStatus
import java.time.Instant

data class CreateReservationResult(
    val reservationId: Long,
    val performanceId: Long,
    val status: ReservationStatus,
    val heldAt: Instant,
    val expiresAt: Instant,
    val quantity: Int,
    val totalAmount: Long,
    val seatIds: List<Long>,
)
