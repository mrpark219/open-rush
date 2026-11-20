package me.park.openrush.payment.application

import me.park.openrush.payment.application.provided.*
import me.park.openrush.payment.application.required.PaymentGatewayPort
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional

@Service
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class PaymentService(private val paymentGateway: PaymentGatewayPort) : ApprovePaymentUseCase, CancelPaymentUseCase {
    override fun cancel(paymentCancellationCommand: PaymentCancellationCommand): CanceledPayment = paymentGateway.cancel(paymentCancellationCommand)

    override fun confirm(paymentApprovalCommand: PaymentApprovalCommand): ApprovedPayment = paymentGateway.confirm(paymentApprovalCommand)

    override fun findByOrder(paymentApprovalCommand: PaymentApprovalCommand): ApprovedPayment? = paymentGateway.findByOrder(paymentApprovalCommand)
}
