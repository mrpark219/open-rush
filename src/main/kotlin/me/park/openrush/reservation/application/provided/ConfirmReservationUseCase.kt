package me.park.openrush.reservation.application.provided

/** 만료 전 선점 예매와 모든 좌석을 함께 확정한다. 확보한 구매 한도는 유지한다. */
fun interface ConfirmReservationUseCase {
    fun confirm(confirmReservationCommand: ConfirmReservationCommand)
}
