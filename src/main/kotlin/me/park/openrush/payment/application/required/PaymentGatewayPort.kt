package me.park.openrush.payment.application.required

import me.park.openrush.payment.application.provided.ApprovedPayment
import me.park.openrush.payment.application.provided.CanceledPayment
import me.park.openrush.payment.application.provided.PaymentApprovalCommand
import me.park.openrush.payment.application.provided.PaymentCancellationCommand

interface PaymentGatewayPort {
    fun cancel(command: PaymentCancellationCommand): CanceledPayment
    fun confirm(command: PaymentApprovalCommand): ApprovedPayment
    fun findByOrder(command: PaymentApprovalCommand): ApprovedPayment?
}
