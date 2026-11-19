package me.park.openrush.reservation.application.provided

import me.park.openrush.reservation.application.exception.ConcurrencyException
import me.park.openrush.reservation.domain.exception.ReservationRuleViolationException

/** 좌석을 선점하고 구매 한도를 확보하여 예매를 생성한다. 실패하면 예매와 자원 확보를 함께 롤백한다. */
interface CreateReservationUseCase {
    @Throws(ReservationRuleViolationException::class, ConcurrencyException::class)
    fun create(
        command: CreateReservationCommand,
        acquireAdmission: (() -> AutoCloseable)? = null
    ): CreateReservationResult
}
