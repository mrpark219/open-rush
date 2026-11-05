package me.park.openrush.reservation.application.port.output

import java.time.Instant

fun interface CancelHeldReservationPort {
    /** 소유자와 유효 기간을 검사하여 HELD 예매를 CANCELLED로 전환한다. 경합으로 실패하면 예외를 던진다. */
    fun cancelHeld(reservationId: Long, userId: Long, now: Instant)
}
