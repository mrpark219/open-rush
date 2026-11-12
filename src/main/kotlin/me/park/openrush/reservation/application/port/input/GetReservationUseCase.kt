package me.park.openrush.reservation.application.port.input

import me.park.openrush.reservation.domain.ReservationStatus
import java.time.Instant

fun interface GetReservationUseCase {
    fun find(reservationId: Long, userId: Long): ReservationDetails?
}

data class ReservationDetails(
    val reservationId: Long,
    val performanceId: Long,
    val status: ReservationStatus,
    val heldAt: Instant,
    val expiresAt: Instant,
    val confirmedAt: Instant?,
    val quantity: Int,
    val totalAmount: Long,
    val seatIds: List<Long>,
)
