package me.park.openrush.concert.adapter.output.persistence.repository

import me.park.openrush.concert.domain.Performance

interface PerformanceRepositoryCustom {
    fun findPerformance(performanceId: Long): Performance?
}
