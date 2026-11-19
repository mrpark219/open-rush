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
class DemoPaymentGatewayAdapter(private val client: DemoPgClient) : PaymentGatewayPort {
    override fun cancel(command: PaymentCancellationCommand): CanceledPayment {
        require(command.provider == "demo-pg" && command.amount > 0)
        val response = try {
            client.cancel(
                command.paymentKey,
                command.idempotencyKey,
                DemoPgCancelRequest(cancelReason = command.reason, cancelAmount = command.amount),
            )
        } catch (exception: FeignException) {
            if (exception.status() in 400..499 && exception.status() !in setOf(408, 409, 429)) {
                throw PaymentGatewayRejectedException(exception)
            }
            throw PaymentGatewayUnavailableException(exception)
        }
        // 전액 환불만 지원한다. 불분명한 응답을 완료로 기록하지 않는다.
        val cancellation = response.cancels.singleOrNull()
        if (response.paymentKey != command.paymentKey || response.orderId != command.orderNumber ||
            response.currency != command.currency || response.totalAmount != command.amount ||
            response.balanceAmount != 0L || response.status != "CANCELED" || cancellation == null ||
            cancellation.cancelAmount != command.amount || cancellation.cancelReason != command.reason ||
            cancellation.cancelStatus != "DONE" || cancellation.transactionKey.isBlank() ||
            cancellation.transactionKey.length > 255
        ) throw PaymentGatewayUnavailableException()
        return CanceledPayment(cancellation.transactionKey)
    }

    override fun confirm(command: PaymentApprovalCommand): ApprovedPayment {
        val response = try {
            client.confirm(
                command.idempotencyKey,
                DemoPgConfirmRequest(
                    paymentKey = command.paymentKey,
                    orderId = command.orderNumber,
                    amount = command.amount,
                ),
            )
        } catch (exception: FeignException) {
            if (exception.status() in 400..499 && exception.status() !in setOf(408, 409, 429)) {
                throw PaymentGatewayRejectedException(exception)
            }
            throw PaymentGatewayUnavailableException(exception)
        }
        return verify(response, command)
    }

    override fun findByOrder(command: PaymentApprovalCommand): ApprovedPayment? {
        val response = try {
            client.findByOrder(command.orderNumber)
        } catch (_: FeignException.NotFound) {
            return null
        } catch (exception: FeignException) {
            throw PaymentGatewayUnavailableException(exception)
        }
        return verify(response, command)
    }

    private fun verify(response: DemoPgPaymentResponse, command: PaymentApprovalCommand): ApprovedPayment {
        if (response.status != "DONE" || response.orderId != command.orderNumber ||
            response.paymentKey != command.paymentKey
        ) throw PaymentGatewayUnavailableException()

        return try {
            // 금액과 통화 불일치는 승인 사실을 보존한 뒤 호출자가 처리한다.
            ApprovedPayment(
                provider = command.provider,
                paymentKey = response.paymentKey,
                amount = response.totalAmount,
                currency = response.currency,
                orderNumber = response.orderId,
            )
        } catch (exception: IllegalArgumentException) {
            throw PaymentGatewayUnavailableException(exception)
        }
    }
}
