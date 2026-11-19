package me.park.openrush.payment.application.provided

/** PG 승인 요청과 주문 조회에 사용하는 정보다. 주문 번호와 멱등 키는 호출자가 유지한다. */
data class PaymentApprovalCommand(
    val provider: String,
    val orderNumber: String,
    val paymentKey: String,
    val amount: Long,
    val idempotencyKey: String,
)
