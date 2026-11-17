package me.park.openrush.payment.adapter.output.pg

data class DemoPgPaymentResponse(
    val paymentKey: String,
    val orderId: String,
    val currency: String,
    val totalAmount: Long,
    val balanceAmount: Long,
    val status: String,
)
