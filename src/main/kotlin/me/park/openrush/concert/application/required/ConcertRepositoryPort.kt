package me.park.openrush.concert.application.required

import me.park.openrush.concert.domain.Concert
import me.park.openrush.concert.domain.Performance

interface ConcertRepositoryPort {
    fun findConcert(concertId: Long): Concert?
    fun findPerformance(performanceId: Long): Performance?
}
