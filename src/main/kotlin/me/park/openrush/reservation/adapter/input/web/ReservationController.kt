package me.park.openrush.reservation.adapter.input.web

import jakarta.validation.Valid
import me.park.openrush.reservation.application.port.input.CreateReservationCommand
import me.park.openrush.reservation.application.port.input.CreateReservationUseCase
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import java.net.URI

@RestController
@RequestMapping("/reservations")
class ReservationController(private val reservations: CreateReservationUseCase) {
    @PostMapping
    fun create(@Valid @RequestBody request: CreateReservationRequest): ResponseEntity<CreateReservationResponse> {
        val command = CreateReservationCommand(
            userId = request.userId,
            performanceId = request.performanceId,
            seatIds = request.seatIds,
        )
        val result = reservations.create(command)

        val response = CreateReservationResponse.from(result)
        val location = URI.create("/reservations/${response.reservationId}")

        return ResponseEntity.created(location).body(response)
    }
}
