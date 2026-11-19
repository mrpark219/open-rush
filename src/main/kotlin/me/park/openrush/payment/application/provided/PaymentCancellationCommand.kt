package me.park.openrush.payment.application.provided

data class PaymentCancellationCommand(
    val provider: String,
    val orderNumber: String,
    val paymentKey: String,
    val amount: Long,
    val currency: String,
    val reason: String,
    val idempotencyKey: String,
)
