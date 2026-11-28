package me.park.openrush.reservation.application.required

import me.park.openrush.reservation.domain.ReservationApprovalRecoveryJob
import me.park.openrush.reservation.domain.ReservationPaymentTransaction
import me.park.openrush.reservation.domain.ReservationRefundJob
import java.time.Instant
import java.util.UUID

interface ReservationPaymentRepositoryPort {
    fun findPaymentTransaction(paymentTransactionId: Long): ReservationPaymentTransaction?
    fun findConfirmedPayment(reservationId: Long): ReservationPaymentTransaction?
    fun findByAttempt(reservationId: Long, attemptNumber: UUID): ReservationPaymentTransaction?
    fun findPreparedByOrder(provider: String, pgOrderNumber: String): ReservationPaymentTransaction?
    fun findCancellationPreparation(reservationId: Long, cancellationNumber: UUID): ReservationPaymentTransaction?
    fun findCanceled(reservationId: Long, cancellationNumber: UUID): ReservationPaymentTransaction?
    fun findApproved(provider: String, paymentId: String): ReservationPaymentTransaction?
    fun save(reservationPaymentTransaction: ReservationPaymentTransaction)

    fun findRefundJob(approvedPaymentTransactionId: Long): ReservationRefundJob?
    fun lockNextDueRefundJob(now: Instant): ReservationRefundJob?
    fun lockDueRefundJob(approvedPaymentTransactionId: Long, now: Instant): ReservationRefundJob?
    fun lockRefundJob(refundJobId: Long): ReservationRefundJob?
    fun saveRefundJob(reservationRefundJob: ReservationRefundJob)

    fun saveApprovalRecoveryJob(approvalRecoveryJob: ReservationApprovalRecoveryJob)
    fun lockNextDueApprovalRecoveryJob(now: Instant): ReservationApprovalRecoveryJob?
    fun lockApprovalRecoveryJob(paymentPreparationId: Long): ReservationApprovalRecoveryJob?
}
