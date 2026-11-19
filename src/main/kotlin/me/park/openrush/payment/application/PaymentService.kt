package me.park.openrush.payment.application

import me.park.openrush.payment.application.provided.ApprovePaymentUseCase
import me.park.openrush.payment.application.provided.ApprovedPayment
import me.park.openrush.payment.application.provided.PaymentApprovalCommand
import me.park.openrush.payment.application.required.PaymentGatewayPort
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional

@Service
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class PaymentService(private val gateway: PaymentGatewayPort) : ApprovePaymentUseCase {
    override fun confirm(command: PaymentApprovalCommand): ApprovedPayment = gateway.confirm(command)

    override fun findByOrder(command: PaymentApprovalCommand): ApprovedPayment? = gateway.findByOrder(command)
}
