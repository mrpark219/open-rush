package me.park.openrush.reservation.adapter.output.persistence.repository

import me.park.openrush.reservation.application.provided.CreateReservationResult
import me.park.openrush.reservation.application.required.ReservationCreationRecord
import me.park.openrush.reservation.domain.Reservation
import java.time.Instant

interface ReservationRepositoryCustom {
    fun findExpiredReservationIds(now: Instant, afterId: Long, limit: Int): List<Long>

    fun expireIfHeld(reservationId: Long, now: Instant): Long

    fun findDetailedById(id: Long): Reservation?

    fun confirmIfHeld(reservationId: Long, userId: Long, now: Instant): Long

    fun cancelIfHeld(reservationId: Long, userId: Long, now: Instant): Long

    fun lockReservation(reservationId: Long): Reservation?

    fun lockOwnedReservation(reservationId: Long, userId: Long): Reservation?

    fun configureLockTimeout(timeoutMillis: Int)

    fun findCreationRecord(userId: Long, key: String): ReservationCreationRecord?

    fun claimCreationRequest(userId: Long, key: String, request: String): Boolean

    fun completeCreationRequest(userId: Long, key: String, result: CreateReservationResult)
}
