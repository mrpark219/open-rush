package me.park.openrush.reservation.application.port.output

import me.park.openrush.reservation.domain.Reservation

fun interface LoadReservationPort {
    /** 예매와 항목, 좌석, 공연을 조회한다. 없으면 null을 반환한다. */
    fun findReservation(reservationId: Long): Reservation?
}
