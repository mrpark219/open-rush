package me.park.openrush.reservation.application.port.input

/** 선점 중인 예매 전체를 취소하고 좌석과 구매 한도를 한 번만 반환한다. 실패하면 모두 롤백한다. */
fun interface CancelReservationHoldUseCase {
    fun cancelHold(command: CancelReservationHoldCommand)
}
