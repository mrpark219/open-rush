package me.park.openrush.reservation.adapter.output.persistence.repository

import me.park.openrush.reservation.domain.ReservationPaymentTransaction
import java.util.*

interface ReservationPaymentRepositoryCustom {
    fun findConfirmedPayment(reservationId: Long): ReservationPaymentTransaction?
    fun findByAttempt(reservationId: Long, attemptNumber: UUID): ReservationPaymentTransaction?
    fun findPreparedByOrder(provider: String, pgOrderNumber: String): ReservationPaymentTransaction?
    fun findCancellationPreparation(reservationId: Long, cancellationNumber: UUID): ReservationPaymentTransaction?
    fun findCanceled(reservationId: Long, cancellationNumber: UUID): ReservationPaymentTransaction?
    fun findApproved(provider: String, paymentId: String): ReservationPaymentTransaction?
}
