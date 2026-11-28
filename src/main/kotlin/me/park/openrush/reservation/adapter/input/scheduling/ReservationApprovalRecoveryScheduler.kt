package me.park.openrush.reservation.adapter.input.scheduling

import me.park.openrush.reservation.application.provided.RecoverReservationPaymentApprovalsUseCase
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component

@Component
@ConditionalOnProperty(prefix = "reservation.approval-recovery", name = ["enabled"], havingValue = "true", matchIfMissing = true)
class ReservationApprovalRecoveryScheduler(
    private val recoverReservationPaymentApprovalsUseCase: RecoverReservationPaymentApprovalsUseCase,
) {
    @Scheduled(
        scheduler = "reservationApprovalRecoveryTaskScheduler",
        fixedDelayString = "\${reservation.approval-recovery.fixed-delay:1s}",
        initialDelayString = "\${reservation.approval-recovery.initial-delay:1s}",
    )
    fun recoverDueApprovals() {
        recoverReservationPaymentApprovalsUseCase.recoverDueApprovals()
    }
}
