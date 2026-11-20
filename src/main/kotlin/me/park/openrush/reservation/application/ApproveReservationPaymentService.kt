package me.park.openrush.reservation.application

import me.park.openrush.payment.application.exception.PaymentGatewayUnavailableException
import me.park.openrush.payment.application.provided.ApprovePaymentUseCase
import me.park.openrush.payment.application.provided.ApprovedPayment
import me.park.openrush.payment.application.provided.PaymentApprovalCommand
import me.park.openrush.reservation.application.exception.PaymentRecordConflictException
import me.park.openrush.reservation.application.provided.*
import me.park.openrush.reservation.domain.PaymentStatus
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional

@Service
class ApproveReservationPaymentService(
    private val prepareReservationPaymentTxUseCase: PrepareReservationPaymentTxUseCase,
    private val reservationPaymentTransactionsUseCase: ReservationPaymentTransactionsUseCase,
    private val approvePaymentUseCase: ApprovePaymentUseCase,
    private val recordPaymentSuccessUseCase: RecordPaymentSuccessUseCase,
    private val refundReservationPaymentUseCase: RefundReservationPaymentUseCase,
) : ApproveReservationPaymentUseCase {
    // 준비와 결과 저장만 각각 트랜잭션을 사용하며 PG 호출 중에는 잠금을 유지하지 않는다.
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    override fun approve(approveReservationPaymentCommand: ApproveReservationPaymentCommand): ReservationPaymentResult {
        val preparedReservationPayment = prepareReservationPaymentTxUseCase.prepare(approveReservationPaymentCommand)
        val paymentPreparation = preparedReservationPayment.transaction
        reservationPaymentTransactionsUseCase.findApproved(
            paymentPreparation.provider,
            approveReservationPaymentCommand.paymentKey,
        )?.let { existingPaymentApprovalResult ->
            if (existingPaymentApprovalResult.payment.reservationId != approveReservationPaymentCommand.reservationId ||
                existingPaymentApprovalResult.payment.pgOrderNumber != paymentPreparation.pgOrderNumber
            ) {
                throw PaymentRecordConflictException()
            }
            return result(approveReservationPaymentCommand, existingPaymentApprovalResult)
        }

        val paymentApprovalCommand = PaymentApprovalCommand(
            provider = paymentPreparation.provider,
            orderNumber = paymentPreparation.pgOrderNumber,
            paymentKey = paymentPreparation.requestedPaymentKey,
            amount = paymentPreparation.amount,
            idempotencyKey = paymentPreparation.approvalIdempotencyKey,
        )
        val approvedPayment: ApprovedPayment? = try {
            approvePaymentUseCase.findByOrder(paymentApprovalCommand) ?: if (preparedReservationPayment.approvalAllowed) {
                try {
                    approvePaymentUseCase.confirm(paymentApprovalCommand)
                } catch (_: PaymentGatewayUnavailableException) {
                    // 승인 응답 유실은 실패로 단정하지 않고 같은 주문으로 조회한다.
                    approvePaymentUseCase.findByOrder(paymentApprovalCommand)
                }
            } else null
        } catch (_: PaymentGatewayUnavailableException) {
            null
        }
        val paymentApprovalResult = approvedPayment?.let {
            recordPaymentSuccessUseCase.record(
                RecordPaymentSuccessCommand(
                    provider = it.provider,
                    paymentId = it.paymentKey,
                    reservationId = approveReservationPaymentCommand.reservationId,
                    amount = it.amount,
                    currency = it.currency,
                    pgOrderNumber = it.orderNumber,
                )
            )
        }
        return result(approveReservationPaymentCommand, paymentApprovalResult)
    }

    private fun result(
        approveReservationPaymentCommand: ApproveReservationPaymentCommand,
        paymentApprovalResult: ReservationPaymentApprovalResult?,
    ): ReservationPaymentResult {
        val paymentRefundResult = if (paymentApprovalResult?.status == PaymentStatus.REFUND_REQUIRED) {
            refundReservationPaymentUseCase.refund(
                RefundReservationPaymentCommand(
                    approveReservationPaymentCommand.reservationId,
                    approveReservationPaymentCommand.userId,
                    approveReservationPaymentCommand.attemptNumber
                )
            )
        } else null
        return ReservationPaymentResult(
            reservationId = approveReservationPaymentCommand.reservationId,
            attemptNumber = approveReservationPaymentCommand.attemptNumber,
            record = paymentApprovalResult,
            refund = paymentRefundResult,
        )
    }
}
