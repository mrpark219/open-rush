package me.park.openrush.payment.application.required

import me.park.openrush.payment.application.provided.ApprovedPayment
import me.park.openrush.payment.application.provided.PaymentApprovalCommand

interface PaymentGatewayPort {
    fun confirm(command: PaymentApprovalCommand): ApprovedPayment
    fun findByOrder(command: PaymentApprovalCommand): ApprovedPayment?
}
