package me.park.openrush.concert.application.provided

import java.time.Instant

data class PerformanceDetails(
    val performanceId: Long,
    val concertId: Long,
    val bookingOpensAt: Instant,
    val bookingClosesAt: Instant,
    val maxTicketsPerUser: Int
)
