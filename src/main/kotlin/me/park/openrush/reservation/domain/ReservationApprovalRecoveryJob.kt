package me.park.openrush.reservation.domain

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.Table
import me.park.openrush.support.persistence.BaseEntity
import java.time.Instant
import java.util.UUID

/** 응답이 확인되지 않은 승인 준비 이력의 주문 조회 일정을 관리한다. */
@Entity
@Table(name = "reservation_approval_recovery_jobs")
class ReservationApprovalRecoveryJob(
    @field:Column(nullable = false, unique = true)
    val paymentPreparationId: Long,

    @field:Column(nullable = false)
    val lookupUntil: Instant,

    nextLookupAt: Instant,
) : BaseEntity() {
    @field:Enumerated(EnumType.STRING)
    @field:Column(nullable = false, length = 32)
    var status: ApprovalRecoveryStatus = ApprovalRecoveryStatus.PENDING
        protected set

    @field:Column(nullable = false)
    var nextLookupAt: Instant = nextLookupAt
        protected set

    var leaseToken: UUID? = null
        protected set

    var leaseExpiresAt: Instant? = null
        protected set

    @field:Column(nullable = false)
    var failureCount: Int = 0
        protected set

    @field:Enumerated(EnumType.STRING)
    @field:Column(length = 32)
    var lastFailure: ApprovalRecoveryFailure? = null
        protected set

    fun claim(token: UUID, now: Instant, expiresAt: Instant) {
        check(status == ApprovalRecoveryStatus.PENDING)
        check(nextLookupAt <= now && leaseExpiresAt?.isAfter(now) != true)

        leaseToken = token
        leaseExpiresAt = expiresAt
    }

    fun recordNotFound(lookedUpAt: Instant, retryAt: Instant) {
        check(status == ApprovalRecoveryStatus.PENDING && leaseToken != null)

        failureCount = 0
        lastFailure = null
        clearLease()

        if (lookedUpAt >= lookupUntil) {
            status = ApprovalRecoveryStatus.NOT_FOUND
        } else {
            nextLookupAt = minOf(retryAt, lookupUntil)
        }
    }

    fun fail(failure: ApprovalRecoveryFailure, retryAt: Instant?) {
        check(status == ApprovalRecoveryStatus.PENDING && leaseToken != null)

        failureCount++
        lastFailure = failure
        clearLease()

        if (retryAt == null) {
            status = ApprovalRecoveryStatus.NEEDS_REVIEW
        } else {
            nextLookupAt = retryAt
        }
    }

    fun complete() {
        status = ApprovalRecoveryStatus.COMPLETED
        clearLease()
    }

    private fun clearLease() {
        leaseToken = null
        leaseExpiresAt = null
    }
}
