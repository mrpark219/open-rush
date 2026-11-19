package me.park.openrush.reservation.application.port.input

import me.park.openrush.reservation.domain.payment.PaymentRecord
import java.util.UUID

data class ReservationPaymentResult(
    val reservationId: Long,
    val attemptId: UUID,
    // null이면 PG 승인 여부를 아직 확정할 수 없다.
    val record: PaymentRecord? = null,
)
