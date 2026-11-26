package me.park.openrush.reservation.application.provided

import java.util.*

/** 결제 시도별 준비·승인 결과를 저장하고 조회한다. 환불 필요 승인에는 작업을 함께 남기며 같은 승인은 중복 기록하지 않는다. */
interface ReservationPaymentTransactionsUseCase {
    fun findPreparation(reservationId: Long, attemptNumber: UUID): PaymentPreparation?
    fun prepare(reservationId: Long, attemptNumber: UUID, amount: Long, paymentKey: String): PaymentPreparation
    fun findApproved(provider: String, paymentId: String): ReservationPaymentApprovalResult?
    fun record(paymentApprovalResult: ReservationPaymentApprovalResult): ReservationPaymentApprovalResult
}
