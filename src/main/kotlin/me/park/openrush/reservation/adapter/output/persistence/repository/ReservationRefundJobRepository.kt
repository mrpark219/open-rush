package me.park.openrush.reservation.adapter.output.persistence.repository

import me.park.openrush.reservation.domain.ReservationRefundJob
import org.springframework.data.jpa.repository.JpaRepository

interface ReservationRefundJobRepository : JpaRepository<ReservationRefundJob, Long> {
    fun findByApprovedPaymentTransactionId(approvedPaymentTransactionId: Long): ReservationRefundJob?
}
