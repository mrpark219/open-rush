package me.park.openrush.reservation.application

import me.park.openrush.payment.application.exception.PaymentGatewayRejectedException
import me.park.openrush.payment.application.exception.PaymentGatewayUnavailableException
import me.park.openrush.payment.application.provided.CancelPaymentUseCase
import me.park.openrush.payment.application.provided.PaymentCancellationCommand
import me.park.openrush.reservation.application.provided.RefundReservationPaymentCommand
import me.park.openrush.reservation.application.provided.RefundReservationPaymentUseCase
import me.park.openrush.reservation.application.provided.ReservationPaymentRefundResult
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional

@Service
class RefundReservationPaymentService(
    private val reservationPaymentRefundTransactions: ReservationPaymentRefundTransactions,
    private val cancelPaymentUseCase: CancelPaymentUseCase,
) : RefundReservationPaymentUseCase {
    private val logger = LoggerFactory.getLogger(javaClass)

    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    override fun refund(refundReservationPaymentCommand: RefundReservationPaymentCommand): ReservationPaymentRefundResult {
        val cancellationPreparation = reservationPaymentRefundTransactions.prepare(refundReservationPaymentCommand)
        val cancellationNumber = requireNotNull(cancellationPreparation.cancellationNumber)
        reservationPaymentRefundTransactions.findCanceled(cancellationPreparation)?.let {
            return ReservationPaymentRefundResult(refundReservationPaymentCommand.reservationId, cancellationNumber, it.cancellationId)
        }
        val canceledPayment = try {
            cancelPaymentUseCase.cancel(
                PaymentCancellationCommand(
                    provider = cancellationPreparation.provider,
                    orderNumber = requireNotNull(cancellationPreparation.pgOrderNumber),
                    paymentKey = requireNotNull(cancellationPreparation.paymentId),
                    amount = cancellationPreparation.amount,
                    currency = requireNotNull(cancellationPreparation.currency),
                    reason = requireNotNull(cancellationPreparation.cancelReason),
                    idempotencyKey = requireNotNull(cancellationPreparation.cancellationIdempotencyKey),
                ),
            )
        } catch (exception: PaymentGatewayRejectedException) {
            logger.warn("PG 환불 거절: reservationId={}, cancellationNumber={}", refundReservationPaymentCommand.reservationId, cancellationNumber, exception)
            return ReservationPaymentRefundResult(refundReservationPaymentCommand.reservationId, cancellationNumber, null)
        } catch (exception: PaymentGatewayUnavailableException) {
            logger.warn(
                "PG 환불 결과 확인 필요: reservationId={}, cancellationNumber={}",
                refundReservationPaymentCommand.reservationId,
                cancellationNumber,
                exception
            )
            return ReservationPaymentRefundResult(refundReservationPaymentCommand.reservationId, cancellationNumber, null)
        }
        val cancellationTransaction = reservationPaymentRefundTransactions.record(cancellationPreparation, canceledPayment.cancellationId)
        return ReservationPaymentRefundResult(refundReservationPaymentCommand.reservationId, cancellationNumber, cancellationTransaction.cancellationId)
    }
}
