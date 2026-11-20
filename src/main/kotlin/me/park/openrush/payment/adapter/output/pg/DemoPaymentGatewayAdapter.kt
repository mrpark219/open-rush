package me.park.openrush.payment.adapter.output.pg

import feign.FeignException
import me.park.openrush.payment.application.exception.PaymentGatewayRejectedException
import me.park.openrush.payment.application.exception.PaymentGatewayUnavailableException
import me.park.openrush.payment.application.provided.ApprovedPayment
import me.park.openrush.payment.application.provided.CanceledPayment
import me.park.openrush.payment.application.provided.PaymentApprovalCommand
import me.park.openrush.payment.application.provided.PaymentCancellationCommand
import me.park.openrush.payment.application.required.PaymentGatewayPort
import org.springframework.stereotype.Component

@Component
class DemoPaymentGatewayAdapter(private val demoPgClient: DemoPgClient) : PaymentGatewayPort {
    override fun cancel(paymentCancellationCommand: PaymentCancellationCommand): CanceledPayment {
        require(paymentCancellationCommand.provider == "demo-pg" && paymentCancellationCommand.amount > 0)
        val paymentResponse = try {
            demoPgClient.cancel(
                paymentCancellationCommand.paymentKey,
                paymentCancellationCommand.idempotencyKey,
                DemoPgCancelRequest(cancelReason = paymentCancellationCommand.reason, cancelAmount = paymentCancellationCommand.amount),
            )
        } catch (exception: FeignException) {
            if (exception.status() in 400..499 && exception.status() !in setOf(408, 409, 429)) {
                throw PaymentGatewayRejectedException(exception)
            }
            throw PaymentGatewayUnavailableException(exception)
        }
        // 전액 환불만 지원한다. 불분명한 응답을 완료로 기록하지 않는다.
        val paymentCancellation = paymentResponse.cancels.singleOrNull()
        if (paymentResponse.paymentKey != paymentCancellationCommand.paymentKey || paymentResponse.orderId != paymentCancellationCommand.orderNumber ||
            paymentResponse.currency != paymentCancellationCommand.currency || paymentResponse.totalAmount != paymentCancellationCommand.amount ||
            paymentResponse.balanceAmount != 0L || paymentResponse.status != "CANCELED" || paymentCancellation == null ||
            paymentCancellation.cancelAmount != paymentCancellationCommand.amount || paymentCancellation.cancelReason != paymentCancellationCommand.reason ||
            paymentCancellation.cancelStatus != "DONE" || paymentCancellation.transactionKey.isBlank() ||
            paymentCancellation.transactionKey.length > 255
        ) throw PaymentGatewayUnavailableException()
        return CanceledPayment(paymentCancellation.transactionKey)
    }

    override fun confirm(paymentApprovalCommand: PaymentApprovalCommand): ApprovedPayment {
        val paymentResponse = try {
            demoPgClient.confirm(
                paymentApprovalCommand.idempotencyKey,
                DemoPgConfirmRequest(
                    paymentKey = paymentApprovalCommand.paymentKey,
                    orderId = paymentApprovalCommand.orderNumber,
                    amount = paymentApprovalCommand.amount,
                ),
            )
        } catch (exception: FeignException) {
            if (exception.status() in 400..499 && exception.status() !in setOf(408, 409, 429)) {
                throw PaymentGatewayRejectedException(exception)
            }
            throw PaymentGatewayUnavailableException(exception)
        }
        return verify(paymentResponse, paymentApprovalCommand)
    }

    override fun findByOrder(paymentApprovalCommand: PaymentApprovalCommand): ApprovedPayment? {
        val paymentResponse = try {
            demoPgClient.findByOrder(paymentApprovalCommand.orderNumber)
        } catch (_: FeignException.NotFound) {
            return null
        } catch (exception: FeignException) {
            throw PaymentGatewayUnavailableException(exception)
        }
        return verify(paymentResponse, paymentApprovalCommand)
    }

    private fun verify(paymentResponse: DemoPgPaymentResponse, paymentApprovalCommand: PaymentApprovalCommand): ApprovedPayment {
        if (paymentResponse.status != "DONE" || paymentResponse.orderId != paymentApprovalCommand.orderNumber ||
            paymentResponse.paymentKey != paymentApprovalCommand.paymentKey
        ) throw PaymentGatewayUnavailableException()

        return try {
            // 금액과 통화 불일치는 승인 사실을 보존한 뒤 호출자가 처리한다.
            ApprovedPayment(
                provider = paymentApprovalCommand.provider,
                paymentKey = paymentResponse.paymentKey,
                amount = paymentResponse.totalAmount,
                currency = paymentResponse.currency,
                orderNumber = paymentResponse.orderId,
            )
        } catch (exception: IllegalArgumentException) {
            throw PaymentGatewayUnavailableException(exception)
        }
    }
}
