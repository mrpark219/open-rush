package me.park.openrush.reservation.adapter.output.persistence.repository

import me.park.openrush.reservation.domain.ReservationRefundJob
import java.time.Instant

interface ReservationRefundJobRepositoryCustom {
    fun lockNextDue(now: Instant): ReservationRefundJob?
    fun lockDue(approvedPaymentTransactionId: Long, now: Instant): ReservationRefundJob?
    fun lockById(refundJobId: Long): ReservationRefundJob?
}
