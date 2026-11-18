package me.park.openrush.reservation.application.port.input

import me.park.openrush.reservation.domain.payment.PaymentRecord
import me.park.openrush.reservation.domain.payment.VerifiedPayment

/** 검증된 결제 성공을 기록한다. 같은 결제의 재전달에는 저장된 결과를 반환한다. */
fun interface RecordPaymentSuccessUseCase {
    fun record(payment: VerifiedPayment): PaymentRecord
}
