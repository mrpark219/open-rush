package me.park.openrush.reservation.application.port.output

import java.time.Instant

fun interface ExpireHeldReservationPort {
    /** HELD이며 expiresAt <= now일 때만 EXPIRED로 전환한다. 전환한 요청만 true를 반환한다. */
    fun expireHeld(reservationId: Long, now: Instant): Boolean
}
