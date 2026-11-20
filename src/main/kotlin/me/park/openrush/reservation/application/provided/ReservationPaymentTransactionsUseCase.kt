package me.park.openrush.reservation.application.provided

import java.util.*

/** 결제 시도별 승인 준비 정보와 승인 결과를 저장하고 조회한다. 같은 승인 결과는 중복 기록하지 않는다. */
interface ReservationPaymentTransactionsUseCase {
    fun findPreparation(reservationId: Long, attemptNumber: UUID): PaymentPreparation?
    fun prepare(reservationId: Long, attemptNumber: UUID, amount: Long, paymentKey: String): PaymentPreparation
    fun findApproved(provider: String, paymentId: String): ReservationPaymentApprovalResult?
    fun record(paymentApprovalResult: ReservationPaymentApprovalResult): ReservationPaymentApprovalResult
}
