package me.park.openrush.reservation.adapter.input.web

import me.park.openrush.reservation.application.port.input.ConfirmReservationCommand
import me.park.openrush.reservation.application.port.input.ConfirmReservationUseCase
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.context.annotation.Profile
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

@RestController
@Profile("benchmark")
@ConditionalOnProperty(prefix = "reservation.benchmark", name = ["rehold-enabled"], havingValue = "true")
class BenchmarkReservationController(private val confirmation: ConfirmReservationUseCase) {
    @PostMapping("/benchmark/reservations/{reservationId}/confirm")
    fun confirm(@PathVariable reservationId: Long, @RequestParam userId: Long): ResponseEntity<Void> {
        confirmation.confirm(ConfirmReservationCommand(userId, reservationId))
        return ResponseEntity.noContent().build()
    }
}
