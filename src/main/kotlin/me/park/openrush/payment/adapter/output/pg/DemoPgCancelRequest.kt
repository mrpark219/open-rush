package me.park.openrush.payment.adapter.output.pg

data class DemoPgCancelRequest(val cancelReason: String, val cancelAmount: Long)
