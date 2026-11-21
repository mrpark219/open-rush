package me.park.openrush.reservation.application.provided

/** 확정 예약을 취소하고 좌석과 구매 한도를 반환한 뒤 환불을 시도한다. 환불 실패는 예약 취소를 되돌리지 않는다. */
interface CancelReservationUseCase {
    fun cancel(cancelReservationCommand: CancelReservationCommand): CancelReservationResult
}
