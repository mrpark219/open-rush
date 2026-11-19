package me.park.openrush.reservation.adapter.input.web

import me.park.openrush.reservation.application.port.input.ReservationPaymentResult
import me.park.openrush.reservation.domain.payment.RefundReason
import java.util.UUID

data class ReservationPaymentResponse(
    val reservationId: Long,
    val attemptId: UUID,
    val status: String,
    val paymentKey: String?,
    val refundReason: RefundReason?,
) {
    companion object {
        fun from(result: ReservationPaymentResult) = ReservationPaymentResponse(
            result.reservationId, result.attemptId, result.record?.status?.name ?: "PENDING",
            result.record?.payment?.paymentId, result.record?.refundReason,
        )
    }
}
