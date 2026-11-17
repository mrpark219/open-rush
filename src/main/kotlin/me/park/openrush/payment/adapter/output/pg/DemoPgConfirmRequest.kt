package me.park.openrush.payment.adapter.output.pg

data class DemoPgConfirmRequest(
    val paymentKey: String,
    val orderId: String,
    val amount: Long,
)
