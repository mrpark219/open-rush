package me.park.openrush.reservation.adapter.input.web

import me.park.openrush.reservation.application.provided.ReservationPaymentResult
import me.park.openrush.reservation.application.provided.ReservationPaymentResultStatus
import me.park.openrush.reservation.domain.RefundReason
import java.util.*

data class ReservationPaymentResponse(
    val reservationId: Long,
    val attemptNumber: UUID,
    val status: ReservationPaymentResultStatus,
    val paymentKey: String?,
    val refundReason: RefundReason?,
) {
    companion object {
        fun from(result: ReservationPaymentResult) = ReservationPaymentResponse(
            reservationId = result.reservationId,
            attemptNumber = result.attemptNumber,
            status = result.status,
            paymentKey = result.record?.payment?.paymentId,
            refundReason = result.record?.refundReason,
        )
    }
}
