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
        require(paymentCancellationCommand.amount <= paymentCancellationCommand.totalAmount)
        require(paymentCancellationCommand.expectedBalanceAmount in 0..(paymentCancellationCommand.totalAmount - paymentCancellationCommand.amount))
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
        return verifyCancellation(paymentResponse, paymentCancellationCommand)
    }

    private fun verifyCancellation(
        paymentResponse: DemoPgPaymentResponse,
        paymentCancellationCommand: PaymentCancellationCommand,
    ): CanceledPayment {
        verifyCanceledPaymentIdentity(paymentResponse, paymentCancellationCommand)
        val expectedStatus = if (paymentCancellationCommand.expectedBalanceAmount == 0L) "CANCELED" else "PARTIAL_CANCELED"
        if (paymentResponse.balanceAmount != paymentCancellationCommand.expectedBalanceAmount ||
            paymentResponse.status != expectedStatus
        ) throw PaymentGatewayUnavailableException()

        // PG는 전체 취소 이력을 반환한다. 이번 요청과 대응하는 성공 이력을 유일하게 식별해야 한다.
        // 응답에 멱등 키가 없으므로 같은 금액과 사유가 여러 건이면 임의로 선택하지 않는다.
        val paymentCancellation = paymentResponse.cancels.filter { cancellation ->
            cancellation.cancelAmount == paymentCancellationCommand.amount &&
                cancellation.cancelReason == paymentCancellationCommand.reason && cancellation.cancelStatus == "DONE"
        }.singleOrNull() ?: throw PaymentGatewayUnavailableException()
        if (paymentCancellation.transactionKey.isBlank() || paymentCancellation.transactionKey.length > 255) {
            throw PaymentGatewayUnavailableException()
        }
        return CanceledPayment(paymentCancellation.transactionKey)
    }

    private fun verifyCanceledPaymentIdentity(
        paymentResponse: DemoPgPaymentResponse,
        paymentCancellationCommand: PaymentCancellationCommand,
    ) {
        if (paymentResponse.paymentKey != paymentCancellationCommand.paymentKey ||
            paymentResponse.orderId != paymentCancellationCommand.orderNumber ||
            paymentResponse.currency != paymentCancellationCommand.currency ||
            paymentResponse.totalAmount != paymentCancellationCommand.totalAmount
        ) throw PaymentGatewayUnavailableException()
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
