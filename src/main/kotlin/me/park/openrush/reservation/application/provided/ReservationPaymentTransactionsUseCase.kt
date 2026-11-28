package me.park.openrush.reservation.application.provided

import java.time.Instant
import java.util.UUID

/** 결제 시도별 준비·승인 결과를 저장하고 조회한다. 승인 확인 작업과 필요한 환불 작업도 같은 트랜잭션에서 관리한다. */
interface ReservationPaymentTransactionsUseCase {
    fun findPreparation(reservationId: Long, attemptNumber: UUID): PaymentPreparation?
    fun prepare(
        reservationId: Long,
        attemptNumber: UUID,
        amount: Long,
        paymentKey: String,
        reservationExpiresAt: Instant,
    ): PaymentPreparation
    fun findApproved(provider: String, paymentId: String): ReservationPaymentApprovalResult?
    fun record(paymentApprovalResult: ReservationPaymentApprovalResult): ReservationPaymentApprovalResult
}
