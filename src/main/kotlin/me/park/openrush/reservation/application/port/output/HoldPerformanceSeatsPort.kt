package me.park.openrush.reservation.application.port.output

import me.park.openrush.common.exception.ConcurrencyException
import me.park.openrush.reservation.domain.exception.ReservationRuleViolation

fun interface HoldPerformanceSeatsPort {
    /**
     * 호출자의 트랜잭션에서 동시성 제어와 좌석 선점을 완료하고 결과를 반환한다.
     * 하나라도 없거나 이미 점유됐다면 예외를 발생시키고 전체를 롤백한다.
     */
    @Throws(ReservationRuleViolation::class, ConcurrencyException::class)
    fun hold(command: HoldSeatsCommand): HoldSeatsResult
}
