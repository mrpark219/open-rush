package me.park.openrush.reservation.application.port.output

import me.park.openrush.reservation.domain.Reservation

interface LockReservationForPaymentPort {
    fun lockReservation(reservationId: Long): Reservation?
    fun lockOwnedReservation(reservationId: Long, userId: Long): Reservation?
}
