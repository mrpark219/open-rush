package me.park.openrush.inventory.application.required

import me.park.openrush.inventory.domain.PerformanceSeat
import java.time.Instant

interface InventoryRepositoryPort {
    fun findSeats(performanceId: Long, seatIds: List<Long>): List<PerformanceSeat>
    fun holdAvailableSeats(performanceId: Long, seatIds: List<Long>, reservationId: Long, modifiedAt: Instant): Long
    fun releaseReservedSeats(reservationId: Long, seatIds: List<Long>, modifiedAt: Instant): Long
    fun confirmHeldSeats(reservationId: Long, seatIds: List<Long>, modifiedAt: Instant): Long
    fun releaseHeldSeats(reservationId: Long, seatIds: List<Long>, modifiedAt: Instant): Long
}
