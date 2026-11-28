package me.park.openrush.reservation.application

import me.park.openrush.reservation.application.required.ReservationPaymentRepositoryPort
import me.park.openrush.reservation.domain.ApprovalRecoveryFailure
import me.park.openrush.reservation.domain.ReservationApprovalRecoveryJob
import me.park.openrush.reservation.domain.ReservationPaymentTransactionType
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
class ReservationApprovalRecoveryTransactions(
    private val reservationPaymentRepository: ReservationPaymentRepositoryPort,
    private val clock: Clock,
) {
    private val logger = LoggerFactory.getLogger(javaClass)

    @Transactional(propagation = Propagation.MANDATORY)
    fun enqueue(paymentPreparationId: Long, reservationExpiresAt: Instant) {
        val recoveryJob = ReservationApprovalRecoveryJob(
            paymentPreparationId = paymentPreparationId,
            lookupUntil = reservationExpiresAt.plus(expirationGracePeriod),
            nextLookupAt = clock.instant().plus(firstLookupDelay),
        )
        reservationPaymentRepository.saveApprovalRecoveryJob(recoveryJob)
    }

    @Transactional(propagation = Propagation.MANDATORY)
    fun complete(paymentPreparationId: Long) {
        // 검증한 PG 승인 결과는 이전 조회의 404나 수동 확인 상태보다 우선한다.
        reservationPaymentRepository.lockApprovalRecoveryJob(paymentPreparationId)?.complete()
    }

    fun claimNext(): ReservationApprovalRecoveryClaim? {
        val now = clock.instant()
        val recoveryJob = reservationPaymentRepository.lockNextDueApprovalRecoveryJob(now) ?: return null
        val token = UUID.randomUUID()
        recoveryJob.claim(token, now, now.plus(leaseDuration))

        val preparation = reservationPaymentRepository.findPaymentTransaction(recoveryJob.paymentPreparationId)
        if (preparation?.type != ReservationPaymentTransactionType.APPROVAL_PREPARED ||
            preparation.requestedPaymentKey.isNullOrBlank()
        ) {
            recoveryJob.fail(ApprovalRecoveryFailure.INVALID_PREPARATION, retryAt = null)
            logger.warn("승인 정보 수동 확인 필요: paymentPreparationId={}", recoveryJob.paymentPreparationId)
            return null
        }

        return ReservationApprovalRecoveryClaim(preparation.toPreparation(), token, now)
    }

    fun recordNotFound(claim: ReservationApprovalRecoveryClaim) {
        val recoveryJob = lockClaimedJob(claim) ?: return
        recoveryJob.recordNotFound(claim.claimedAt, clock.instant().plus(notFoundLookupDelay))
    }

    fun fail(claim: ReservationApprovalRecoveryClaim, failure: ApprovalRecoveryFailure) {
        val recoveryJob = lockClaimedJob(claim) ?: return
        val retryDelay = if (failure.retryable) retryDelays.getOrNull(recoveryJob.failureCount) else null
        val retryAt = retryDelay?.let { clock.instant().plus(it) }
        recoveryJob.fail(failure, retryAt)

        if (retryAt == null) {
            logger.warn("승인 결과 수동 확인 필요: paymentPreparationId={}, failure={}", recoveryJob.paymentPreparationId, failure)
        } else {
            logger.warn(
                "승인 결과 재조회 예정: paymentPreparationId={}, failure={}, nextLookupAt={}",
                recoveryJob.paymentPreparationId, failure, retryAt,
            )
        }
    }

    private fun lockClaimedJob(claim: ReservationApprovalRecoveryClaim): ReservationApprovalRecoveryJob? {
        val recoveryJob = reservationPaymentRepository.lockApprovalRecoveryJob(claim.paymentPreparation.id)
        return recoveryJob?.takeIf { it.leaseToken == claim.token }
    }

    private companion object {
        val firstLookupDelay = Duration.ofSeconds(5)
        val notFoundLookupDelay = Duration.ofSeconds(10)
        val expirationGracePeriod = Duration.ofMinutes(2)
        val leaseDuration = Duration.ofSeconds(30)
        val retryDelays = listOf(5L, 15L, 45L, 135L).map(Duration::ofSeconds)
    }
}
