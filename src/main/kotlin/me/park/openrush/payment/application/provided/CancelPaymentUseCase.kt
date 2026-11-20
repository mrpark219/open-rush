package me.park.openrush.payment.application.provided

/** 같은 멱등 키로 PG 전액 환불을 요청한다. 호출 중에는 DB 트랜잭션을 유지하지 않는다. */
interface CancelPaymentUseCase {
    fun cancel(paymentCancellationCommand: PaymentCancellationCommand): CanceledPayment
}
