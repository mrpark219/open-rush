package me.park.openrush.reservation.application.port.input

fun interface ExpireReservationHoldUseCase {
    /** 예매 한 건을 독립된 트랜잭션으로 만료한다. 대상이 없거나 이미 처리됐거나 기한 전이면 false를 반환한다. */
    fun expireHold(reservationId: Long): Boolean
}
