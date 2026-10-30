package me.park.openrush.reservation.adapter.input.web

import jakarta.validation.constraints.NotEmpty
import jakarta.validation.constraints.Positive

data class CreateReservationRequest(
    @field:Positive val userId: Long,
    @field:Positive val performanceId: Long,
    @field:NotEmpty val seatIds: List<@Positive Long>,
)
