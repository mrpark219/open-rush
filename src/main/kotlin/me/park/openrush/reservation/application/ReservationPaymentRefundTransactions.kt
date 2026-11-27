package me.park.openrush.reservation.application

import me.park.openrush.reservation.application.exception.PaymentRecordConflictException
import me.park.openrush.reservation.application.provided.RefundReservationPaymentCommand
import me.park.openrush.reservation.application.required.ReservationPaymentRepositoryPort
import me.park.openrush.reservation.application.required.ReservationRepositoryPort
import me.park.openrush.reservation.domain.PaymentStatus
import me.park.openrush.reservation.domain.Reservation
import me.park.openrush.reservation.domain.ReservationPaymentTransaction
import me.park.openrush.reservation.domain.ReservationPaymentTransactionType
import me.park.openrush.reservation.domain.ReservationStatus
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import java.time.Clock

@Service
@Transactional(propagation = Propagation.REQUIRES_NEW, timeout = 5)
class ReservationPaymentRefundTransactions(
    private val reservationRepository: ReservationRepositoryPort,
    private val reservationPaymentRepository: ReservationPaymentRepositoryPort,
    private val reservationRefundJobService: ReservationRefundJobService,
    private val clock: Clock,
) {
    fun prepare(refundReservationPaymentCommand: RefundReservationPaymentCommand): PreparedReservationRefund {
        val reservation = reservationRepository.lockOwnedReservation(
            refundReservationPaymentCommand.reservationId,
            refundReservationPaymentCommand.userId,
        ) ?: throw PaymentRecordConflictException()
        val paymentPreparation = reservationPaymentRepository.findByAttempt(
            refundReservationPaymentCommand.reservationId,
            refundReservationPaymentCommand.attemptNumber,
        ) ?: throw PaymentRecordConflictException()
        val approvedPaymentTransaction = reservationPaymentRepository.findApproved(
            paymentPreparation.provider,
            paymentPreparation.requestedPaymentKey ?: throw PaymentRecordConflictException(),
        ) ?: throw PaymentRecordConflictException()

        if (approvedPaymentTransaction.reservationId != refundReservationPaymentCommand.reservationId ||
            approvedPaymentTransaction.pgOrderNumber != paymentPreparation.pgOrderNumber
        ) {
            throw PaymentRecordConflictException()
        }

        val cancellationPreparation = prepareCancellation(reservation, approvedPaymentTransaction)
        reservationRefundJobService.enqueue(approvedPaymentTransaction)

        return PreparedReservationRefund(requireNotNull(approvedPaymentTransaction.id), cancellationPreparation)
    }

    fun prepareForClaim(claim: ReservationRefundClaim): ReservationPaymentTransaction? {
        val approval = reservationPaymentRepository.findPaymentTransaction(claim.approvedPaymentTransactionId)
            ?: throw PaymentRecordConflictException()
        val reservation = reservationRepository.lockReservation(approval.reservationId)
            ?: throw PaymentRecordConflictException()
        val refundJob = reservationPaymentRepository.lockRefundJob(claim.jobId)
        if (refundJob == null || refundJob.leaseToken != claim.token) {
            return null
        }

        return prepareCancellation(reservation, approval)
    }

    private fun prepareCancellation(
        reservation: Reservation,
        approval: ReservationPaymentTransaction,
    ): ReservationPaymentTransaction {
        if (approval.type != ReservationPaymentTransactionType.APPROVED || approval.amount <= 0 ||
            approval.pgOrderNumber.isNullOrBlank() || approval.paymentId.isNullOrBlank() || approval.currency == null
        ) {
            throw PaymentRecordConflictException()
        }

        // 준비를 다시 실행해도 승인 행마다 같은 취소 번호와 PG 멱등 키를 사용한다.
        val cancellationNumber = approval.refundCancellationNumber()
        val existingPreparation = reservationPaymentRepository.findCancellationPreparation(approval.reservationId, cancellationNumber)
        when (approval.status) {
            PaymentStatus.RESERVATION_CONFIRMED -> {
                if (reservation.status != ReservationStatus.CANCELLED ||
                    reservation.confirmedAt == null || existingPreparation == null
                ) {
                    throw PaymentRecordConflictException()
                }
            }
            PaymentStatus.REFUND_REQUIRED -> Unit
            else -> throw PaymentRecordConflictException()
        }

        if (existingPreparation != null) {
            if (existingPreparation.amount != approval.amount ||
                existingPreparation.provider != approval.provider ||
                existingPreparation.paymentId != approval.paymentId ||
                existingPreparation.pgOrderNumber != approval.pgOrderNumber ||
                existingPreparation.currency != approval.currency ||
                existingPreparation.cancelReason.isNullOrBlank() || existingPreparation.cancellationIdempotencyKey.isNullOrBlank()
            ) {
                throw PaymentRecordConflictException()
            }

            return existingPreparation
        }

        val newPreparation = approval.prepareCancellation(
            cancellationNumber = cancellationNumber,
            cancelAmount = approval.amount,
            cancelReason = approval.refundReason?.name ?: throw PaymentRecordConflictException(),
        )
        reservationPaymentRepository.save(newPreparation)

        return newPreparation
    }

    fun findCanceled(cancellationPreparation: ReservationPaymentTransaction): ReservationPaymentTransaction? =
        reservationPaymentRepository.findCanceled(
            cancellationPreparation.reservationId,
            requireNotNull(cancellationPreparation.cancellationNumber),
        )

    fun record(
        claim: ReservationRefundClaim,
        cancellationPreparation: ReservationPaymentTransaction,
        cancellationId: String,
    ): ReservationPaymentTransaction? {
        reservationRepository.lockReservation(cancellationPreparation.reservationId) ?: throw PaymentRecordConflictException()
        val refundJob = reservationPaymentRepository.lockRefundJob(claim.jobId)
        if (refundJob == null || refundJob.leaseToken != claim.token) {
            return null
        }

        val approval = reservationPaymentRepository.findPaymentTransaction(refundJob.approvedPaymentTransactionId)
            ?: throw PaymentRecordConflictException()
        if (approval.reservationId != cancellationPreparation.reservationId ||
            approval.provider != cancellationPreparation.provider ||
            approval.paymentId != cancellationPreparation.paymentId ||
            approval.amount != cancellationPreparation.amount ||
            approval.currency != cancellationPreparation.currency
        ) {
            throw PaymentRecordConflictException()
        }

        val existingCancellation = findCanceled(cancellationPreparation)
        if (existingCancellation != null) {
            if (existingCancellation.cancellationId != cancellationId || existingCancellation.amount != approval.amount) {
                throw PaymentRecordConflictException()
            }

            refundJob.complete(requireNotNull(existingCancellation.recordedAt))

            return existingCancellation
        }

        val cancellation = cancellationPreparation.canceled(cancellationId)
        reservationPaymentRepository.save(cancellation)
        refundJob.complete(clock.instant())

        return cancellation
    }
}
