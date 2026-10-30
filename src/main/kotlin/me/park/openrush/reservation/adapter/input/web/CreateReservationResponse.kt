package me.park.openrush.reservation.adapter.input.web

import me.park.openrush.reservation.application.port.input.CreateReservationResult
import java.time.Instant

data class CreateReservationResponse(
    val reservationId: Long,
    val performanceId: Long,
    val status: String,
    val heldAt: Instant,
    val expiresAt: Instant,
    val quantity: Int,
    val totalAmount: Long,
    val seatIds: List<Long>,
) {
    companion object {
        fun from(result: CreateReservationResult): CreateReservationResponse = CreateReservationResponse(
            reservationId = result.reservationId,
            performanceId = result.performanceId,
            status = result.status.name,
            heldAt = result.heldAt,
            expiresAt = result.expiresAt,
            quantity = result.quantity,
            totalAmount = result.totalAmount,
            seatIds = result.seatIds,
        )
    }
}
