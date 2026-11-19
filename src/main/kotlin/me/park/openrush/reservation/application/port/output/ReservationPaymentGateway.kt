package me.park.openrush.reservation.application.port.output

import me.park.openrush.reservation.domain.payment.ReservationPaymentTx
import me.park.openrush.reservation.domain.payment.VerifiedPayment

interface ReservationPaymentGateway {
    fun confirm(preparation: ReservationPaymentTx): VerifiedPayment
    fun findByOrder(preparation: ReservationPaymentTx): VerifiedPayment?
}
