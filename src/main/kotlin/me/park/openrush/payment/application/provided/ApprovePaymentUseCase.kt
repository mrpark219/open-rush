package me.park.openrush.payment.application.provided

/** PG에 결제 승인을 요청하거나 주문별 승인 결과를 조회한다. 호출 중에는 DB 트랜잭션을 유지하지 않는다. */
interface ApprovePaymentUseCase {
    fun confirm(command: PaymentApprovalCommand): ApprovedPayment
    fun findByOrder(command: PaymentApprovalCommand): ApprovedPayment?
}
