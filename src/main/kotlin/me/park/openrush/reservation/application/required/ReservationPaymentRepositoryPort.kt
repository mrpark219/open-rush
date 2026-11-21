package me.park.openrush.reservation.application.required

import me.park.openrush.reservation.domain.ReservationPaymentTransaction
import java.util.UUID

interface ReservationPaymentRepositoryPort {
    fun findConfirmedPayment(reservationId: Long): ReservationPaymentTransaction?
    fun findByAttempt(reservationId: Long, attemptNumber: UUID): ReservationPaymentTransaction?
    fun findPreparedByOrder(provider: String, pgOrderNumber: String): ReservationPaymentTransaction?
    fun findCancellationPreparation(reservationId: Long, cancellationNumber: UUID): ReservationPaymentTransaction?
    fun findCanceled(reservationId: Long, cancellationNumber: UUID): ReservationPaymentTransaction?
    fun findApproved(provider: String, paymentId: String): ReservationPaymentTransaction?
    fun save(reservationPaymentTransaction: ReservationPaymentTransaction)
}
