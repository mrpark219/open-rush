package me.park.openrush.reservation.adapter.output.persistence.repository

import me.park.openrush.reservation.domain.ReservationApprovalRecoveryJob
import java.time.Instant

interface ReservationApprovalRecoveryJobRepositoryCustom {
    fun lockNextDue(now: Instant): ReservationApprovalRecoveryJob?
    fun lockByPreparationId(paymentPreparationId: Long): ReservationApprovalRecoveryJob?
}
