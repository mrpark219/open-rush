package me.park.openrush.reservation.adapter.input.web

import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Positive
import jakarta.validation.constraints.Size

data class CancelReservationRequest(
    @field:Positive val userId: Long,
    @field:NotBlank @field:Size(max = 255) val cancelReason: String,
)
