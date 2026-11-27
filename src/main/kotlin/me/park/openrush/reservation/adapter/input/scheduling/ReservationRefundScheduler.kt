package me.park.openrush.reservation.adapter.input.scheduling

import me.park.openrush.reservation.application.provided.ProcessReservationRefundJobsUseCase
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component

@Component
@ConditionalOnProperty(prefix = "reservation.refund", name = ["enabled"], havingValue = "true", matchIfMissing = true)
class ReservationRefundScheduler(private val processReservationRefundJobsUseCase: ProcessReservationRefundJobsUseCase) {
    @Scheduled(
        scheduler = "reservationRefundTaskScheduler",
        fixedDelayString = "\${reservation.refund.fixed-delay:1s}",
        initialDelayString = "\${reservation.refund.initial-delay:1s}",
    )
    fun processDueRefunds() {
        processReservationRefundJobsUseCase.processDueRefunds()
    }
}
