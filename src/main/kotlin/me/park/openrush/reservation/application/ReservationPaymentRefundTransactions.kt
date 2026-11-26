package me.park.openrush.reservation.application

import me.park.openrush.reservation.domain.ReservationStatus
import me.park.openrush.reservation.application.exception.PaymentRecordConflictException
import me.park.openrush.reservation.application.provided.RefundReservationPaymentCommand
import me.park.openrush.reservation.application.required.ReservationPaymentRepositoryPort
import me.park.openrush.reservation.application.required.ReservationRepositoryPort
import me.park.openrush.reservation.domain.PaymentStatus
import me.park.openrush.reservation.domain.ReservationPaymentTransaction
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional

@Service
@Transactional(propagation = Propagation.REQUIRES_NEW)
class ReservationPaymentRefundTransactions(
    private val reservationRepository: ReservationRepositoryPort,
    private val reservationPaymentRepository: ReservationPaymentRepositoryPort,
    private val reservationRefundJobService: ReservationRefundJobService,
) {
    fun prepare(refundReservationPaymentCommand: RefundReservationPaymentCommand): ReservationPaymentTransaction {
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
            requireNotNull(paymentPreparation.requestedPaymentKey),
        ) ?: throw PaymentRecordConflictException()

        if (approvedPaymentTransaction.reservationId != refundReservationPaymentCommand.reservationId ||
            approvedPaymentTransaction.pgOrderNumber != paymentPreparation.pgOrderNumber ||
            approvedPaymentTransaction.amount <= 0
        ) {
            throw PaymentRecordConflictException()
        }

        // 승인 행마다 하나의 환불 번호를 사용하여 다른 요청에서도 중복 환불을 막는다.
        val cancellationNumber = approvedPaymentTransaction.refundCancellationNumber()
        val cancellationPreparation = reservationPaymentRepository.findCancellationPreparation(
            refundReservationPaymentCommand.reservationId,
            cancellationNumber,
        )
        if (approvedPaymentTransaction.status == PaymentStatus.RESERVATION_CONFIRMED) {
            if (reservation.status != ReservationStatus.CANCELLED ||
                reservation.confirmedAt == null || cancellationPreparation == null
            ) {
                throw PaymentRecordConflictException()
            }

            reservationRefundJobService.enqueue(approvedPaymentTransaction)

            return cancellationPreparation
        }

        if (approvedPaymentTransaction.status != PaymentStatus.REFUND_REQUIRED) {
            throw PaymentRecordConflictException()
        }

        reservationRefundJobService.enqueue(approvedPaymentTransaction)
        if (cancellationPreparation != null) {
            return cancellationPreparation
        }

        val newCancellationPreparation = approvedPaymentTransaction.prepareCancellation(
            cancellationNumber = cancellationNumber,
            cancelAmount = approvedPaymentTransaction.amount,
            cancelReason = requireNotNull(approvedPaymentTransaction.refundReason).name,
        )
        reservationPaymentRepository.save(newCancellationPreparation)

        return newCancellationPreparation
    }

    fun findCanceled(cancellationPreparation: ReservationPaymentTransaction): ReservationPaymentTransaction? =
        reservationPaymentRepository.findCanceled(
            cancellationPreparation.reservationId,
            requireNotNull(cancellationPreparation.cancellationNumber),
        )

    fun record(cancellationPreparation: ReservationPaymentTransaction, cancellationId: String): ReservationPaymentTransaction {
        reservationRepository.lockReservation(cancellationPreparation.reservationId) ?: throw PaymentRecordConflictException()
        val approvedPaymentTransaction = reservationPaymentRepository.findApproved(
            cancellationPreparation.provider,
            requireNotNull(cancellationPreparation.paymentId),
        ) ?: throw PaymentRecordConflictException()
        if (approvedPaymentTransaction.reservationId != cancellationPreparation.reservationId) {
            throw PaymentRecordConflictException()
        }

        val existingCancellationTransaction = reservationPaymentRepository.findCanceled(
            cancellationPreparation.reservationId,
            requireNotNull(cancellationPreparation.cancellationNumber),
        )
        if (existingCancellationTransaction != null) {
            if (existingCancellationTransaction.cancellationId != cancellationId) {
                throw PaymentRecordConflictException()
            }

            reservationRefundJobService.complete(approvedPaymentTransaction)

            return existingCancellationTransaction
        }

        val cancellationTransaction = cancellationPreparation.canceled(cancellationId)
        reservationPaymentRepository.save(cancellationTransaction)
        reservationRefundJobService.complete(approvedPaymentTransaction)

        return cancellationTransaction
    }
}
