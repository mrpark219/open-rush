package me.park.openrush.reservation.application.provided

/** 기한이 지난 선점 예매 한 건을 독립된 트랜잭션으로 만료하고 좌석과 구매 한도를 반환한다. */
fun interface ExpireReservationHoldUseCase {
    /** 예매 한 건을 독립된 트랜잭션으로 만료한다. 대상이 없거나 이미 처리됐거나 기한 전이면 false를 반환한다. */
    fun expireHold(reservationId: Long): Boolean
}
