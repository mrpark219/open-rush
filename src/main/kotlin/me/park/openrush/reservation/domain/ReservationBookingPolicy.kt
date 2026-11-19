package me.park.openrush.reservation.domain

import java.time.Instant

data class ReservationBookingPolicy(
    val performanceId: Long?,
    val bookingOpensAt: Instant,
    val bookingClosesAt: Instant,
    val maxTicketsPerUser: Int
) {
    fun isBookingOpen(now: Instant): Boolean = now in bookingOpensAt..<bookingClosesAt
}
