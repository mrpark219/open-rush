package me.park.openrush.reservation.adapter.output.persistence.repository

import java.time.Instant
import me.park.openrush.reservation.domain.Reservation

interface ReservationRepositoryCustom {
    fun findDetailedById(id: Long): Reservation?

    fun cancelIfHeld(reservationId: Long, userId: Long, now: Instant): Long
}
