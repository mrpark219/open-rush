package me.park.openrush.reservation.adapter.input.web

import jakarta.validation.Valid
import me.park.openrush.queue.application.provided.ReservationAdmissionUseCase
import me.park.openrush.reservation.application.provided.CancelReservationHoldCommand
import me.park.openrush.reservation.application.provided.CancelReservationHoldUseCase
import me.park.openrush.reservation.application.provided.CreateReservationCommand
import me.park.openrush.reservation.application.provided.CreateReservationUseCase
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.*
import java.net.URI

@RestController
@RequestMapping("/reservations")
class ReservationController(
    private val reservations: CreateReservationUseCase,
    private val holdCancellations: CancelReservationHoldUseCase,
    private val queue: ReservationAdmissionUseCase? = null,
) {
    @PostMapping("/{reservationId}/cancel-hold")
    fun cancelHold(
        @PathVariable reservationId: Long,
        @Valid @RequestBody request: CancelReservationHoldRequest,
    ): ResponseEntity<Void> {
        holdCancellations.cancelHold(CancelReservationHoldCommand(request.userId, reservationId))
        return ResponseEntity.noContent().build()
    }

    @PostMapping
    fun create(
        @Valid @RequestBody request: CreateReservationRequest,
        @RequestHeader(name = "X-Queue-Ticket", required = false) queueTicket: String? = null,
        @RequestHeader(name = "Idempotency-Key", required = false) idempotencyKey: String? = null,
    ): ResponseEntity<CreateReservationResponse> {
        val command = CreateReservationCommand(
            userId = request.userId,
            performanceId = request.performanceId,
            seatIds = request.seatIds,
            idempotencyKey = idempotencyKey,
        )
        val result = reservations.create(command, queue?.let { { it.consume(command.userId, queueTicket) } })

        val response = CreateReservationResponse.from(result)
        val location = URI.create("/reservations/${response.reservationId}")

        return ResponseEntity.created(location).body(response)
    }
}
