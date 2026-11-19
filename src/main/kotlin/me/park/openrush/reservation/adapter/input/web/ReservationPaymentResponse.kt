package me.park.openrush.reservation.adapter.input.web

import me.park.openrush.reservation.application.provided.ReservationPaymentResult
import me.park.openrush.reservation.domain.RefundReason
import java.util.UUID

data class ReservationPaymentResponse(
    val reservationId: Long,
    val attemptNumber: UUID,
    val status: String,
    val paymentKey: String?,
    val refundReason: RefundReason?,
) {
    companion object {
        fun from(result: ReservationPaymentResult) = ReservationPaymentResponse(
            result.reservationId, result.attemptNumber, result.record?.status?.name ?: "PENDING",
            result.record?.payment?.paymentId, result.record?.refundReason,
        )
    }
}
