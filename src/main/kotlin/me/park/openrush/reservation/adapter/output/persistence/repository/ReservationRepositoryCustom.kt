package me.park.openrush.reservation.adapter.output.persistence.repository

import java.time.Instant
import me.park.openrush.reservation.domain.Reservation

interface ReservationRepositoryCustom {
    fun findExpiredReservationIds(now: Instant, afterId: Long, limit: Int): List<Long>

    fun expireIfHeld(reservationId: Long, now: Instant): Long

    fun findDetailedById(id: Long): Reservation?

    fun confirmIfHeld(reservationId: Long, userId: Long, now: Instant): Long

    fun cancelIfHeld(reservationId: Long, userId: Long, now: Instant): Long
}
