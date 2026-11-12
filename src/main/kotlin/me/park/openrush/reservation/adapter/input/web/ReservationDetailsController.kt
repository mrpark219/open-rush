package me.park.openrush.reservation.adapter.input.web

import me.park.openrush.reservation.application.port.input.GetReservationUseCase
import me.park.openrush.reservation.application.port.input.ReservationDetails
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

@RestController
class ReservationDetailsController(private val reservations: GetReservationUseCase) {
    @GetMapping("/reservations/{reservationId}")
    fun get(
        @PathVariable reservationId: Long,
        @RequestParam userId: Long,
    ): ResponseEntity<ReservationDetails> =
        ResponseEntity.ofNullable(reservations.find(reservationId, userId))
}
