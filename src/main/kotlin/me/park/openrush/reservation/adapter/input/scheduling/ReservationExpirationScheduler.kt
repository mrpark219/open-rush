package me.park.openrush.reservation.adapter.input.scheduling

import me.park.openrush.reservation.application.port.input.ExpireReservationHoldsUseCase
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component

@Component
@ConditionalOnProperty(prefix = "reservation.expiration", name = ["enabled"], havingValue = "true", matchIfMissing = true)
class ReservationExpirationScheduler(private val expiration: ExpireReservationHoldsUseCase) {
    @Scheduled(fixedDelayString = "\${reservation.expiration.fixed-delay:10s}",
        initialDelayString = "\${reservation.expiration.initial-delay:10s}")
    fun expireHolds() {
        expiration.expireHolds()
    }
}
