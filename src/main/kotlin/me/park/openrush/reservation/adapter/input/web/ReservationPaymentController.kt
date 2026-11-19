package me.park.openrush.reservation.adapter.input.web

import jakarta.validation.Valid
import me.park.openrush.reservation.application.provided.ApproveReservationPaymentCommand
import me.park.openrush.reservation.application.provided.ApproveReservationPaymentUseCase
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.*

@RestController
@RequestMapping("/reservations/{reservationId}")
class ReservationPaymentController(private val payments: ApproveReservationPaymentUseCase) {
    @PostMapping("/confirm")
    fun confirm(
        @PathVariable reservationId: Long,
        @Valid @RequestBody request: ApproveReservationPaymentRequest,
    ): ResponseEntity<ReservationPaymentResponse> {
        val result = payments.approve(
            ApproveReservationPaymentCommand(
                reservationId,
                request.userId,
                request.attemptNumber,
                request.paymentKey
            )
        )
        val response = ReservationPaymentResponse.from(result)
        return if (result.record == null) ResponseEntity.accepted().body(response) else ResponseEntity.ok(response)
    }
}
