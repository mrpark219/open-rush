package me.park.openrush.reservation.application.provided

/** 예매 소유자와 결제 가능 여부를 검증하고 결제 승인 준비 정보를 생성하거나 기존 시도 정보를 반환한다. */
fun interface PrepareReservationPaymentTxUseCase {
    fun prepare(command: ApproveReservationPaymentCommand): PreparedReservationPayment
}
