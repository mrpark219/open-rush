package me.park.openrush.reservation.application.provided

/** 예매 결제의 준비, PG 승인 조회와 요청, 결과 기록을 조율한다. 승인 여부가 불명확하면 확인 대기 결과를 반환한다. */
fun interface ApproveReservationPaymentUseCase {
    fun approve(command: ApproveReservationPaymentCommand): ReservationPaymentResult
}
