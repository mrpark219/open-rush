package me.park.openrush.reservation.adapter.output.persistence.repository

import me.park.openrush.reservation.domain.seat.PerformanceSeat
import java.time.Instant

interface SeatRepositoryCustom {
    fun confirmHeldSeats(reservationId: Long, seatIds: List<Long>, modifiedAt: Instant): Long

    fun releaseHeldSeats(reservationId: Long, seatIds: List<Long>, modifiedAt: Instant): Long

    fun findSeats(performanceId: Long, seatIds: List<Long>): List<PerformanceSeat>

    fun holdAvailableSeats(
        performanceId: Long,
        seatIds: List<Long>,
        reservationId: Long,
        modifiedAt: Instant,
    ): Long
}
