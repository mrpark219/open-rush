package me.park.openrush.reservation.adapter.input.web

import jakarta.validation.Valid
import java.net.URI
import me.park.openrush.reservation.application.ReservationQueue
import me.park.openrush.reservation.application.port.input.CancelReservationHoldCommand
import me.park.openrush.reservation.application.port.input.CancelReservationHoldUseCase
import me.park.openrush.reservation.application.port.input.CreateReservationCommand
import me.park.openrush.reservation.application.port.input.CreateReservationUseCase
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/reservations")
class ReservationController(
    private val reservations: CreateReservationUseCase,
    private val holdCancellations: CancelReservationHoldUseCase,
    private val queue: ReservationQueue? = null,
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
    ): ResponseEntity<CreateReservationResponse> {
        val command = CreateReservationCommand(
            userId = request.userId,
            performanceId = request.performanceId,
            seatIds = request.seatIds,
        )
        val result = queue?.consume(command.userId, queueTicket).use { reservations.create(command) }

        val response = CreateReservationResponse.from(result)
        val location = URI.create("/reservations/${response.reservationId}")

        return ResponseEntity.created(location).body(response)
    }
}
