package me.park.openrush.reservation.application.port.output

import me.park.openrush.common.exception.ConcurrencyException
import me.park.openrush.reservation.domain.exception.ReservationRuleViolation

fun interface HoldPerformanceSeatsPort {
    /**
     * 호출자의 트랜잭션에서 요청한 좌석을 모두 해당 예매에 선점한다.
     * 하나라도 없거나 이미 점유됐다면 예외를 발생시키고 전체를 롤백한다.
     */
    @Throws(ReservationRuleViolation::class, ConcurrencyException::class)
    fun hold(command: HoldSeatsCommand)
}
