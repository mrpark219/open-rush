package me.park.openrush.reservation.application

import me.park.openrush.reservation.application.required.ReservationPaymentRepositoryPort
import me.park.openrush.reservation.domain.RefundJobFailure
import me.park.openrush.reservation.domain.ReservationRefundJob
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.UUID

@Service
@Transactional(propagation = Propagation.REQUIRES_NEW, timeout = 5)
class ReservationRefundJobTransactions(
    private val reservationPaymentRepository: ReservationPaymentRepositoryPort,
    private val clock: Clock,
) {
    private val logger = LoggerFactory.getLogger(javaClass)

    fun claimNext(): ReservationRefundClaim? = claim(reservationPaymentRepository::lockNextDueRefundJob)

    fun claimPayment(approvedPaymentTransactionId: Long): ReservationRefundClaim? =
        claim { now -> reservationPaymentRepository.lockDueRefundJob(approvedPaymentTransactionId, now) }

    fun fail(claim: ReservationRefundClaim, failure: RefundJobFailure) {
        val refundJob = reservationPaymentRepository.lockRefundJob(claim.jobId)
        if (refundJob == null || refundJob.leaseToken != claim.token) {
            return
        }

        val retryDelay = if (failure.retryable) retryDelays.getOrNull(refundJob.failureCount) else null
        val retryAt = retryDelay?.let { clock.instant().plus(it) }
        refundJob.fail(failure, retryAt)

        if (retryAt == null) {
            logger.warn("환불 작업 수동 확인 필요: jobId={}, failure={}", claim.jobId, failure)
        } else {
            logger.warn("환불 작업 재시도 예정: jobId={}, failure={}, nextAttemptAt={}", claim.jobId, failure, retryAt)
        }
    }

    private fun claim(lockJob: (Instant) -> ReservationRefundJob?): ReservationRefundClaim? {
        val now = clock.instant()
        val refundJob = lockJob(now) ?: return null
        val token = UUID.randomUUID()
        refundJob.claim(token, now, now.plus(leaseDuration))

        return ReservationRefundClaim(requireNotNull(refundJob.id), refundJob.approvedPaymentTransactionId, token)
    }

    private companion object {
        val leaseDuration = Duration.ofSeconds(30)
        val retryDelays = listOf(5L, 15L, 45L, 135L).map(Duration::ofSeconds)
    }
}
