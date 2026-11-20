package me.park.openrush.reservation.application.provided


/** 검증된 결제 성공을 기록하고 예매를 확정하거나 환불 필요 상태로 기록한다. 같은 결제의 재전달에는 저장된 결과를 반환한다. */
fun interface RecordPaymentSuccessUseCase {
    fun record(recordPaymentSuccessCommand: RecordPaymentSuccessCommand): ReservationPaymentApprovalResult
}
