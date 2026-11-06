package me.park.openrush.reservation.application.port.output

import java.time.Instant

fun interface FindExpiredReservationHoldsPort {
    /** HELD이며 expiresAt <= now인 ID를 afterId 초과부터 ID 오름차순으로 최대 limit건 조회한다. */
    fun findExpiredReservationIds(now: Instant, afterId: Long, limit: Int): List<Long>
}
