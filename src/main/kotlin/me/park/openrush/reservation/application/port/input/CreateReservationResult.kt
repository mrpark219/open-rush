package me.park.openrush.reservation.application.port.input

import me.park.openrush.reservation.application.port.output.HoldSeatsResult
import me.park.openrush.reservation.domain.Reservation
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
) {
    companion object {
        fun from(
            reservation: Reservation,
            heldSeats: HoldSeatsResult,
        ): CreateReservationResult = CreateReservationResult(
            reservationId = requireNotNull(reservation.id),
            performanceId = requireNotNull(reservation.performance.id),
            status = reservation.status,
            heldAt = reservation.heldAt,
            expiresAt = reservation.expiresAt,
            quantity = heldSeats.quantity,
            totalAmount = reservation.totalAmount.amount,
            seatIds = heldSeats.seatIds,
        )
    }
}
