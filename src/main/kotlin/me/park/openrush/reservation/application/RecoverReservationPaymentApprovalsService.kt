package me.park.openrush.reservation.application

import me.park.openrush.payment.application.exception.PaymentGatewayUnavailableException
import me.park.openrush.payment.application.provided.ApprovePaymentUseCase
import me.park.openrush.reservation.application.exception.PaymentRecordConflictException
import me.park.openrush.reservation.application.provided.RecordPaymentSuccessUseCase
import me.park.openrush.reservation.application.provided.RecoverReservationPaymentApprovalsUseCase
import me.park.openrush.reservation.domain.ApprovalRecoveryFailure
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional

@Service
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class RecoverReservationPaymentApprovalsService(
    private val reservationApprovalRecoveryTransactions: ReservationApprovalRecoveryTransactions,
    private val approvePaymentUseCase: ApprovePaymentUseCase,
    private val recordPaymentSuccessUseCase: RecordPaymentSuccessUseCase,
) : RecoverReservationPaymentApprovalsUseCase {
    private val logger = LoggerFactory.getLogger(javaClass)

    override fun recoverDueApprovals() {
        repeat(10) {
            val claim = reservationApprovalRecoveryTransactions.claimNext() ?: return
            try {
                recover(claim)
            } catch (exception: RuntimeException) {
                logger.error("승인 복구 작업 처리 실패: paymentPreparationId={}", claim.paymentPreparation.id, exception)
            }
        }
    }

    private fun recover(claim: ReservationApprovalRecoveryClaim) {
        val paymentPreparation = claim.paymentPreparation
        try {
            val approvedPayment = approvePaymentUseCase.findByOrder(paymentPreparation.toPaymentApprovalCommand())
            if (approvedPayment == null) {
                reservationApprovalRecoveryTransactions.recordNotFound(claim)
                return
            }

            recordPaymentSuccessUseCase.record(approvedPayment.toRecordPaymentSuccessCommand(paymentPreparation.reservationId))
        } catch (_: PaymentGatewayUnavailableException) {
            reservationApprovalRecoveryTransactions.fail(claim, ApprovalRecoveryFailure.PG_UNAVAILABLE)
        } catch (_: PaymentRecordConflictException) {
            reservationApprovalRecoveryTransactions.fail(claim, ApprovalRecoveryFailure.PAYMENT_CONFLICT)
        } catch (exception: RuntimeException) {
            try {
                reservationApprovalRecoveryTransactions.fail(claim, ApprovalRecoveryFailure.RECORD_FAILED)
            } catch (stateWriteException: RuntimeException) {
                exception.addSuppressed(stateWriteException)
            }
            throw exception
        }
    }
}
