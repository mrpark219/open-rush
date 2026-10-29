package me.park.openrush.reservation.application.port.output

import me.park.openrush.reservation.domain.Reservation

fun interface SaveReservationPort {
    /** 호출자의 트랜잭션에서 예매와 항목을 저장하고 ID가 발급된 예매를 반환한다. */
    fun save(reservation: Reservation): Reservation
}
