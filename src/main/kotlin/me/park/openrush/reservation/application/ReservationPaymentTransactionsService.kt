package me.park.openrush.reservation.application

import me.park.openrush.reservation.application.exception.DuplicateApprovedPaymentException
import me.park.openrush.reservation.application.exception.PaymentRecordConflictException
import me.park.openrush.reservation.application.provided.PaymentPreparation
import me.park.openrush.reservation.application.provided.RecordPaymentSuccessCommand
import me.park.openrush.reservation.application.provided.ReservationPaymentApprovalResult
import me.park.openrush.reservation.application.provided.ReservationPaymentTransactionsUseCase
import me.park.openrush.reservation.application.required.ReservationPaymentRepositoryPort
import me.park.openrush.reservation.domain.PaymentStatus
import me.park.openrush.reservation.domain.ReservationPaymentTransaction
import me.park.openrush.reservation.domain.ReservationPaymentTransactionType
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Instant
import java.util.UUID

@Service
class ReservationPaymentTransactionsService(
    private val reservationPaymentRepository: ReservationPaymentRepositoryPort,
    private val reservationRefundJobService: ReservationRefundJobService,
    private val reservationApprovalRecoveryTransactions: ReservationApprovalRecoveryTransactions,
) : ReservationPaymentTransactionsUseCase {
    @Transactional(readOnly = true)
    override fun findPreparation(reservationId: Long, attemptNumber: UUID): PaymentPreparation? =
        reservationPaymentRepository.findByAttempt(reservationId, attemptNumber)?.toPreparation()

    @Transactional
    override fun prepare(
        reservationId: Long,
        attemptNumber: UUID,
        amount: Long,
        paymentKey: String,
        reservationExpiresAt: Instant,
    ): PaymentPreparation {
        val paymentPreparation = ReservationPaymentTransaction.prepare(reservationId, attemptNumber, amount, paymentKey)
        reservationPaymentRepository.save(paymentPreparation)
        val preparation = paymentPreparation.toPreparation()
        reservationApprovalRecoveryTransactions.enqueue(preparation.id, reservationExpiresAt)

        return preparation
    }

    @Transactional(readOnly = true)
    override fun findApproved(provider: String, paymentId: String): ReservationPaymentApprovalResult? =
        reservationPaymentRepository.findApproved(provider, paymentId)?.toApprovalResult()

    @Transactional
    override fun record(paymentApprovalResult: ReservationPaymentApprovalResult): ReservationPaymentApprovalResult {
        val recordPaymentSuccessCommand = paymentApprovalResult.payment
        reservationPaymentRepository.findApproved(recordPaymentSuccessCommand.provider, recordPaymentSuccessCommand.paymentId)?.toApprovalResult()?.let {
            if (it.payment != recordPaymentSuccessCommand) throw PaymentRecordConflictException()
            return it
        }
        val paymentPreparation = recordPaymentSuccessCommand.pgOrderNumber?.let { pgOrderNumber ->
            reservationPaymentRepository.findPreparedByOrder(recordPaymentSuccessCommand.provider, pgOrderNumber)
                ?.takeIf { it.reservationId == recordPaymentSuccessCommand.reservationId }
                ?: throw IllegalArgumentException("결제의 PG 주문이 예매와 일치하지 않습니다.")
        }
        val approvedPaymentTransaction = ReservationPaymentTransaction.approved(
            reservationId = recordPaymentSuccessCommand.reservationId,
            provider = recordPaymentSuccessCommand.provider,
            paymentId = recordPaymentSuccessCommand.paymentId,
            amount = recordPaymentSuccessCommand.amount,
            currency = recordPaymentSuccessCommand.currency,
            pgOrderNumber = recordPaymentSuccessCommand.pgOrderNumber,
            status = paymentApprovalResult.status,
            refundReason = paymentApprovalResult.refundReason,
            preparation = paymentPreparation,
        )
        try {
            reservationPaymentRepository.save(approvedPaymentTransaction)
        } catch (exception: DuplicateApprovedPaymentException) {
            throw PaymentRecordConflictException(exception)
        }

        if (paymentApprovalResult.status == PaymentStatus.REFUND_REQUIRED && approvedPaymentTransaction.amount > 0) {
            reservationRefundJobService.enqueue(approvedPaymentTransaction)
        }

        if (paymentPreparation != null) {
            reservationApprovalRecoveryTransactions.complete(requireNotNull(paymentPreparation.id))
        }

        return paymentApprovalResult
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
}
