package me.park.openrush.reservation.application.port.input

import me.park.openrush.common.exception.ConcurrencyException
import me.park.openrush.reservation.domain.exception.ReservationRuleViolation

/**
 * 좌석을 예매하고 구매 한도를 확보한다. 실패하면 모든 변경을 롤백한다.
 * 구현 방식이 달라도 예매 정책과 트랜잭션 외부 재시도 기준은 같아야 한다.
 */
fun interface CreateReservationUseCase {
    @Throws(ReservationRuleViolation::class, ConcurrencyException::class)
    fun create(command: CreateReservationCommand): CreateReservationResult
}
