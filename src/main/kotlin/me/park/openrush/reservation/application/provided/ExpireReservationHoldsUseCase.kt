package me.park.openrush.reservation.application.provided

/** 실행 시작 시각까지 만료된 선점 예매를 나누어 조회하여 처리한다. 한 건의 실패가 다른 예매 처리를 막지 않는다. */
fun interface ExpireReservationHoldsUseCase {
    /** 실행 시작 시각까지 만료된 예매를 나누어 조회한다. 한 건의 실패가 다른 예매 처리를 막지 않는다. */
    fun expireHolds(): ExpireReservationHoldsResult
}
