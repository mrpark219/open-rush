package me.park.openrush.reservation.adapter.output.persistence.repository

import me.park.openrush.concert.domain.Performance

interface PerformanceRepositoryCustom {
    fun findPerformance(performanceId: Long): Performance?
}
