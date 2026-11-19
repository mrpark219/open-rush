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
    private val transactions: ReservationPaymentRefundTransactions,
    private val gateway: CancelPaymentUseCase,
) : RefundReservationPaymentUseCase {
    private val logger = LoggerFactory.getLogger(javaClass)

    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    override fun refund(command: RefundReservationPaymentCommand): ReservationPaymentRefundResult {
        val preparation = transactions.prepare(command)
        val number = requireNotNull(preparation.cancellationNumber)
        transactions.findCanceled(preparation)?.let {
            return ReservationPaymentRefundResult(command.reservationId, number, it.cancellationId)
        }
        val canceled = try {
            gateway.cancel(
                PaymentCancellationCommand(
                    provider = preparation.provider,
                    orderNumber = requireNotNull(preparation.pgOrderNumber),
                    paymentKey = requireNotNull(preparation.paymentId),
                    amount = preparation.amount,
                    currency = requireNotNull(preparation.currency),
                    reason = requireNotNull(preparation.cancelReason),
                    idempotencyKey = requireNotNull(preparation.cancellationIdempotencyKey),
                ),
            )
        } catch (exception: PaymentGatewayRejectedException) {
            logger.warn("PG 환불 거절: reservationId={}, cancellationNumber={}", command.reservationId, number, exception)
            return ReservationPaymentRefundResult(command.reservationId, number, null)
        } catch (exception: PaymentGatewayUnavailableException) {
            logger.warn(
                "PG 환불 결과 확인 필요: reservationId={}, cancellationNumber={}",
                command.reservationId,
                number,
                exception
            )
            return ReservationPaymentRefundResult(command.reservationId, number, null)
        }
        val recorded = transactions.record(preparation, canceled.cancellationId)
        return ReservationPaymentRefundResult(command.reservationId, number, recorded.cancellationId)
    }
}
