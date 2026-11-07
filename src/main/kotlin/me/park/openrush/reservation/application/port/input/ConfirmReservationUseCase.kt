package me.park.openrush.reservation.application.port.input

/** 만료 전 선점 예매와 모든 좌석을 함께 확정한다. 구매 한도는 유지한다. */
fun interface ConfirmReservationUseCase {
    fun confirm(command: ConfirmReservationCommand)
}
