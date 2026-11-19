package me.park.openrush.reservation.application.provided

import java.util.*

data class ReservationPaymentResult(
    val reservationId: Long,
    val attemptNumber: UUID,
    // null이면 PG 승인 여부를 아직 확정할 수 없다.
    val record: ReservationPaymentApprovalResult? = null,
)
