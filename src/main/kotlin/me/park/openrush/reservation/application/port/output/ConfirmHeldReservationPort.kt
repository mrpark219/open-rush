package me.park.openrush.reservation.application.port.output

import java.time.Instant

fun interface ConfirmHeldReservationPort {
    /** 전달된 시각에 소유자와 선점 기한이 유효한 예매를 확정한다. 전환하지 못하면 예외를 던진다. */
    fun confirmHeld(reservationId: Long, userId: Long, now: Instant)
}
