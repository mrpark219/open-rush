package me.park.openrush.reservation.application.port.output

import me.park.openrush.reservation.domain.seat.PerformanceSeat

fun interface LoadPerformanceSeatsPort {
    /**
     * 해당 회차의 좌석을 쓰기 잠금 없이 ID 오름차순으로 조회한다. 없는 좌석은 제외한다.
     * 조회한 좌석은 예매 검증과 가격 기록에만 사용한다.
     */
    fun findSeats(performanceId: Long, seatIds: List<Long>): List<PerformanceSeat>
}
