package me.park.openrush.reservation.application

import me.park.openrush.payment.application.exception.PaymentGatewayRejectedException
import me.park.openrush.payment.application.exception.PaymentGatewayUnavailableException
import me.park.openrush.payment.application.provided.CancelPaymentUseCase
import me.park.openrush.payment.application.provided.PaymentCancellationCommand
import me.park.openrush.reservation.application.exception.PaymentRecordConflictException
import me.park.openrush.reservation.application.provided.ProcessReservationRefundJobsUseCase
import me.park.openrush.reservation.application.provided.RefundReservationPaymentCommand
import me.park.openrush.reservation.application.provided.RefundReservationPaymentUseCase
import me.park.openrush.reservation.application.provided.ReservationPaymentRefundResult
import me.park.openrush.reservation.domain.RefundJobFailure
import me.park.openrush.reservation.domain.ReservationPaymentTransaction
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional

@Service
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class RefundReservationPaymentService(
    private val reservationPaymentRefundTransactions: ReservationPaymentRefundTransactions,
    private val reservationRefundJobTransactions: ReservationRefundJobTransactions,
    private val cancelPaymentUseCase: CancelPaymentUseCase,
) : RefundReservationPaymentUseCase, ProcessReservationRefundJobsUseCase {
    private val logger = LoggerFactory.getLogger(javaClass)

    override fun refund(refundReservationPaymentCommand: RefundReservationPaymentCommand): ReservationPaymentRefundResult {
        val preparedRefund = reservationPaymentRefundTransactions.prepare(refundReservationPaymentCommand)
        val cancellationPreparation = preparedRefund.cancellationPreparation
        val claim = reservationRefundJobTransactions.claimPayment(preparedRefund.approvedPaymentTransactionId)
        val cancellationId = if (claim == null) {
            reservationPaymentRefundTransactions.findCanceled(cancellationPreparation)?.cancellationId
        } else {
            processClaim(claim) { cancellationPreparation }
        }

        return ReservationPaymentRefundResult(
            cancellationPreparation.reservationId,
            requireNotNull(cancellationPreparation.cancellationNumber),
            cancellationId,
        )
    }

    override fun processDueRefunds() {
        repeat(10) {
            val claim = reservationRefundJobTransactions.claimNext() ?: return
            try {
                processClaim(claim) { reservationPaymentRefundTransactions.prepareForClaim(claim) }
            } catch (exception: RuntimeException) {
                logger.error("환불 작업 실행 실패: jobId={}", claim.jobId, exception)
            }
        }
    }

    private fun processClaim(
        claim: ReservationRefundClaim,
        prepare: () -> ReservationPaymentTransaction?,
    ): String? {
        try {
            val cancellationPreparation = prepare() ?: return null
            val existingCancellation = reservationPaymentRefundTransactions.findCanceled(cancellationPreparation)
            val cancellationId = existingCancellation?.cancellationId ?: cancelAtGateway(cancellationPreparation)

            return reservationPaymentRefundTransactions.record(claim, cancellationPreparation, cancellationId)?.cancellationId
        } catch (_: PaymentGatewayRejectedException) {
            reservationRefundJobTransactions.fail(claim, RefundJobFailure.PG_REJECTED)
        } catch (_: PaymentGatewayUnavailableException) {
            reservationRefundJobTransactions.fail(claim, RefundJobFailure.PG_UNAVAILABLE)
        } catch (_: PaymentRecordConflictException) {
            reservationRefundJobTransactions.fail(claim, RefundJobFailure.INVALID_PAYMENT_DATA)
        } catch (exception: RuntimeException) {
            try {
                reservationRefundJobTransactions.fail(claim, RefundJobFailure.PROCESSING_ERROR)
            } catch (stateWriteException: RuntimeException) {
                // 상태 저장이 불가능하면 점유 만료 후 다시 가져간다.
                exception.addSuppressed(stateWriteException)
            }
            throw exception
        }

        return null
    }

    private fun cancelAtGateway(cancellationPreparation: ReservationPaymentTransaction): String {
        try {
            return cancelPaymentUseCase.cancel(
                PaymentCancellationCommand(
                    provider = cancellationPreparation.provider,
                    orderNumber = requireNotNull(cancellationPreparation.pgOrderNumber),
                    paymentKey = requireNotNull(cancellationPreparation.paymentId),
                    amount = cancellationPreparation.amount,
                    currency = requireNotNull(cancellationPreparation.currency),
                    reason = requireNotNull(cancellationPreparation.cancelReason),
                    idempotencyKey = requireNotNull(cancellationPreparation.cancellationIdempotencyKey),
                ),
            ).cancellationId
        } catch (exception: IllegalArgumentException) {
            throw PaymentRecordConflictException(exception)
        }
    }
}
