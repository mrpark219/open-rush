package me.park.openrush.payment.adapter.output.pg

data class DemoPgCancelResponse(
    val transactionKey: String,
    val cancelAmount: Long,
    val cancelReason: String,
    val cancelStatus: String,
)
