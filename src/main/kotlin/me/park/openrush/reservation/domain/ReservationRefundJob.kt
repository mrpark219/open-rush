package me.park.openrush.reservation.domain

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.Table
import me.park.openrush.support.persistence.BaseEntity
import java.time.Instant

/** 전액 환불할 승인 이력을 참조한다. 거래 이력과 분리해 작업 진행 상태를 관리한다. */
@Entity
@Table(name = "reservation_refund_jobs")
class ReservationRefundJob(
    @field:Column(nullable = false, unique = true)
    val approvedPaymentTransactionId: Long,
) : BaseEntity() {
    @field:Enumerated(EnumType.STRING)
    @field:Column(nullable = false, length = 32)
    var status: ReservationRefundJobStatus = ReservationRefundJobStatus.PENDING
        protected set

    var completedAt: Instant? = null
        protected set

    init {
        require(approvedPaymentTransactionId > 0)
    }

    fun complete(completedAt: Instant) {
        if (status == ReservationRefundJobStatus.COMPLETED) {
            return
        }

        status = ReservationRefundJobStatus.COMPLETED
        this.completedAt = completedAt
    }
}
