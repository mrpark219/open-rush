package me.park.openrush.reservation.application.provided

/** 예약 취소 또는 확정 실패로 등록된 전액 환불 작업을 즉시 실행하거나 현재 결과를 반환한다. */
interface RefundReservationPaymentUseCase {
    fun refund(refundReservationPaymentCommand: RefundReservationPaymentCommand): ReservationPaymentRefundResult
}
