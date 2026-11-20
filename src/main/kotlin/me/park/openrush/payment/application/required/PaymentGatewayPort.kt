package me.park.openrush.payment.application.required

import me.park.openrush.payment.application.provided.ApprovedPayment
import me.park.openrush.payment.application.provided.CanceledPayment
import me.park.openrush.payment.application.provided.PaymentApprovalCommand
import me.park.openrush.payment.application.provided.PaymentCancellationCommand

interface PaymentGatewayPort {
    fun cancel(paymentCancellationCommand: PaymentCancellationCommand): CanceledPayment
    fun confirm(paymentApprovalCommand: PaymentApprovalCommand): ApprovedPayment
    fun findByOrder(paymentApprovalCommand: PaymentApprovalCommand): ApprovedPayment?
}
