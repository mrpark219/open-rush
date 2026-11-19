package me.park.openrush.reservation.adapter.input.web

import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Positive
import jakarta.validation.constraints.Size
import java.util.UUID

data class ApproveReservationPaymentRequest(
    @field:Positive val userId: Long,
    val attemptId: UUID,
    @field:NotBlank @field:Size(max = 200) val paymentKey: String,
)
