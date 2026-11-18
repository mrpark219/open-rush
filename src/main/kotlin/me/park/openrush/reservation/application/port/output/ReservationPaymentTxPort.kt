package me.park.openrush.reservation.application.port.output

import me.park.openrush.reservation.domain.payment.ReservationPaymentTx
import java.util.UUID

interface ReservationPaymentTxPort {
    fun findByAttempt(reservationId: Long, attemptId: UUID): ReservationPaymentTx?
    fun findPreparedByOrder(provider: String, pgOrderId: String): ReservationPaymentTx?
    fun findApproved(provider: String, paymentId: String): ReservationPaymentTx?
    fun save(paymentTx: ReservationPaymentTx)
}
