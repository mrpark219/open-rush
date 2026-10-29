package me.park.openrush.reservation.application.port.output

import me.park.openrush.common.exception.ConcurrencyException
import me.park.openrush.reservation.domain.exception.ReservationRuleViolation

fun interface AllocatePurchaseQuotaPort {
    /**
     * 사용자의 공연별 구매 한도 안에서 수량을 확보한다. 한도 정보가 없으면 생성한다.
     * 호출자의 트랜잭션에서 처리하고 예매가 실패하면 함께 롤백한다.
     */
    @Throws(ReservationRuleViolation::class, ConcurrencyException::class)
    fun allocate(command: AllocateQuotaCommand)
}
