package me.park.openrush.reservation.application

import me.park.openrush.reservation.application.exception.PaymentRecordConflictException
import me.park.openrush.reservation.application.provided.RefundReservationPaymentCommand
import me.park.openrush.reservation.application.required.ReservationPaymentRepositoryPort
import me.park.openrush.reservation.application.required.ReservationRepositoryPort
import me.park.openrush.reservation.domain.PaymentStatus
import me.park.openrush.reservation.domain.ReservationPaymentTransaction
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import java.util.*

@Service
@Transactional(propagation = Propagation.REQUIRES_NEW)
class ReservationPaymentRefundTransactions(
    private val reservations: ReservationRepositoryPort,
    private val payments: ReservationPaymentRepositoryPort,
) {
    fun prepare(command: RefundReservationPaymentCommand): ReservationPaymentTransaction {
        reservations.lockOwnedReservation(command.reservationId, command.userId)
            ?: throw PaymentRecordConflictException()
        val attempt = payments.findByAttempt(command.reservationId, command.attemptNumber)
            ?: throw PaymentRecordConflictException()
        val approval = payments.findApproved(attempt.provider, requireNotNull(attempt.requestedPaymentKey))
            ?: throw PaymentRecordConflictException()
        if (approval.reservationId != command.reservationId || approval.pgOrderNumber != attempt.pgOrderNumber ||
            approval.status != PaymentStatus.REFUND_REQUIRED || approval.amount <= 0
        ) throw PaymentRecordConflictException()

        // 승인 행마다 하나의 환불 번호를 사용하여 다른 요청에서도 중복 환불을 막는다.
        val number =
            UUID.nameUUIDFromBytes("refund-${approval.provider}-${approval.paymentId}".toByteArray(Charsets.UTF_8))
        return payments.findCancellationPreparation(command.reservationId, number)
            ?: approval.prepareCancellation(
                cancellationNumber = number,
                cancelAmount = approval.amount,
                cancelReason = requireNotNull(approval.refundReason).name,
            ).also { payments.save(it) }
    }

    fun findCanceled(preparation: ReservationPaymentTransaction): ReservationPaymentTransaction? =
        payments.findCanceled(preparation.reservationId, requireNotNull(preparation.cancellationNumber))

    fun record(preparation: ReservationPaymentTransaction, cancellationId: String): ReservationPaymentTransaction {
        reservations.lockReservation(preparation.reservationId) ?: throw PaymentRecordConflictException()
        val existing = payments.findCanceled(preparation.reservationId, requireNotNull(preparation.cancellationNumber))
        if (existing != null) {
            if (existing.cancellationId != cancellationId) throw PaymentRecordConflictException()
            return existing
        }
        return preparation.canceled(cancellationId).also { payments.save(it) }
    }
}
