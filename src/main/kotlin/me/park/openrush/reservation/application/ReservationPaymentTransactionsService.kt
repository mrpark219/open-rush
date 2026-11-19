package me.park.openrush.reservation.application

import me.park.openrush.reservation.application.provided.PaymentPreparation
import me.park.openrush.reservation.application.exception.PaymentRecordConflictException
import me.park.openrush.reservation.application.provided.ReservationPaymentTransactionsUseCase
import me.park.openrush.reservation.application.exception.DuplicateApprovedPaymentException
import me.park.openrush.reservation.application.required.ReservationPaymentRepositoryPort
import me.park.openrush.reservation.application.provided.ReservationPaymentApprovalResult
import me.park.openrush.reservation.application.provided.RecordPaymentSuccessCommand
import me.park.openrush.reservation.domain.ReservationPaymentTransactionType
import me.park.openrush.reservation.domain.ReservationPaymentTransaction
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.util.*

@Service
class ReservationPaymentTransactionsService(
    private val transactions: ReservationPaymentRepositoryPort,
) : ReservationPaymentTransactionsUseCase {
    @Transactional(readOnly = true)
    override fun findPreparation(reservationId: Long, attemptNumber: UUID): PaymentPreparation? =
        transactions.findByAttempt(reservationId, attemptNumber)?.toPreparation()

    @Transactional
    override fun prepare(reservationId: Long, attemptNumber: UUID, amount: Long, paymentKey: String): PaymentPreparation {
        val tx = ReservationPaymentTransaction.prepare(reservationId, attemptNumber, amount, paymentKey)
        transactions.save(tx)
        return tx.toPreparation()
    }

    @Transactional(readOnly = true)
    override fun findApproved(provider: String, paymentId: String): ReservationPaymentApprovalResult? =
        transactions.findApproved(provider, paymentId)?.toApprovalResult()

    @Transactional
    override fun record(record: ReservationPaymentApprovalResult): ReservationPaymentApprovalResult {
        val payment = record.payment
        transactions.findApproved(payment.provider, payment.paymentId)?.toApprovalResult()?.let {
            if (it.payment != payment) throw PaymentRecordConflictException()
            return it
        }
        val preparation = payment.pgOrderNumber?.let { order ->
            transactions.findPreparedByOrder(payment.provider, order)
                ?.takeIf { it.reservationId == payment.reservationId }
                ?: throw IllegalArgumentException("결제의 PG 주문이 예매와 일치하지 않습니다.")
        }
        try {
            transactions.save(
                ReservationPaymentTransaction.approved(
                    reservationId = payment.reservationId,
                    provider = payment.provider,
                    paymentId = payment.paymentId,
                    amount = payment.amount,
                    currency = payment.currency,
                    pgOrderNumber = payment.pgOrderNumber,
                    status = record.status,
                    refundReason = record.refundReason,
                    preparation = preparation,
                )
            )
        } catch (exception: DuplicateApprovedPaymentException) {
            throw PaymentRecordConflictException(exception)
        }
        return record
    }

    private fun ReservationPaymentTransaction.toApprovalResult(): ReservationPaymentApprovalResult {
        check(type == ReservationPaymentTransactionType.APPROVED)
        return ReservationPaymentApprovalResult(
            payment = RecordPaymentSuccessCommand(
                provider = provider,
                paymentId = requireNotNull(paymentId),
                reservationId = reservationId,
                amount = amount,
                currency = requireNotNull(currency),
                pgOrderNumber = pgOrderNumber,
            ),
            status = requireNotNull(status),
            refundReason = refundReason,
        )
    }

    private fun ReservationPaymentTransaction.toPreparation() = PaymentPreparation(
        id = requireNotNull(id),
        reservationId = reservationId,
        attemptNumber = requireNotNull(attemptNumber),
        provider = provider,
        amount = amount,
        pgOrderNumber = requireNotNull(pgOrderNumber),
        approvalIdempotencyKey = requireNotNull(approvalIdempotencyKey),
        requestedPaymentKey = requireNotNull(requestedPaymentKey),
    )
}
