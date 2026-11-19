package me.park.openrush.reservation.application.provided

/** 예약 확정 실패로 환불이 필요한 결제를 전액 환불한다. 예약 상태와 좌석은 변경하지 않는다. */
interface RefundReservationPaymentUseCase {
    fun refund(command: RefundReservationPaymentCommand): ReservationPaymentRefundResult
}
