package me.park.openrush.reservation.adapter.input.web

import jakarta.validation.Valid
import me.park.openrush.reservation.application.port.input.ApproveReservationPaymentCommand
import me.park.openrush.reservation.application.port.input.ApproveReservationPaymentUseCase
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/reservations/{reservationId}/payments")
class ReservationPaymentController(private val payments: ApproveReservationPaymentUseCase) {
    @PostMapping("/confirm")
    fun confirm(
        @PathVariable reservationId: Long,
        @Valid @RequestBody request: ApproveReservationPaymentRequest,
    ): ResponseEntity<ReservationPaymentResponse> {
        val result = payments.approve(ApproveReservationPaymentCommand(reservationId, request.userId, request.attemptId, request.paymentKey))
        val response = ReservationPaymentResponse.from(result)
        return if (result.record == null) ResponseEntity.accepted().body(response) else ResponseEntity.ok(response)
    }
}
