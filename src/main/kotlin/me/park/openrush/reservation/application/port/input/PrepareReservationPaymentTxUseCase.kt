package me.park.openrush.reservation.application.port.input

import me.park.openrush.reservation.domain.payment.ReservationPaymentTx
import java.util.UUID

fun interface PrepareReservationPaymentTxUseCase {
    fun prepare(reservationId: Long, userId: Long, attemptId: UUID): ReservationPaymentTx
}
