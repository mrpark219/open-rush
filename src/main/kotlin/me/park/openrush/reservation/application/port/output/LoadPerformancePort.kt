package me.park.openrush.reservation.application.port.output

import me.park.openrush.concert.domain.Performance

fun interface LoadPerformancePort {
    /** 회차와 공연 정보를 조회한다. 없으면 null을 반환한다. */
    fun findPerformance(performanceId: Long): Performance?
}
