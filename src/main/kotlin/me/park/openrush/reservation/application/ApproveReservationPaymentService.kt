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
    private val preparations: PrepareReservationPaymentTxUseCase,
    private val transactions: ReservationPaymentTransactionsUseCase,
    private val gateway: ApprovePaymentUseCase,
    private val records: RecordPaymentSuccessUseCase,
    private val refunds: RefundReservationPaymentUseCase,
) : ApproveReservationPaymentUseCase {
    // 준비와 결과 저장만 각각 트랜잭션을 사용하며 PG 호출 중에는 잠금을 유지하지 않는다.
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    override fun approve(command: ApproveReservationPaymentCommand): ReservationPaymentResult {
        val prepared = preparations.prepare(command)
        val tx = prepared.transaction
        transactions.findApproved(tx.provider, command.paymentKey)?.let {
            if (it.payment.reservationId != command.reservationId || it.payment.pgOrderNumber != tx.pgOrderNumber) {
                throw PaymentRecordConflictException()
            }
            return result(command, it)
        }

        val approvalCommand = PaymentApprovalCommand(
            provider = tx.provider,
            orderNumber = tx.pgOrderNumber,
            paymentKey = tx.requestedPaymentKey,
            amount = tx.amount,
            idempotencyKey = tx.approvalIdempotencyKey,
        )
        val payment: ApprovedPayment? = try {
            gateway.findByOrder(approvalCommand) ?: if (prepared.approvalAllowed) {
                try {
                    gateway.confirm(approvalCommand)
                } catch (_: PaymentGatewayUnavailableException) {
                    // 승인 응답 유실은 실패로 단정하지 않고 같은 주문으로 조회한다.
                    gateway.findByOrder(approvalCommand)
                }
            } else null
        } catch (_: PaymentGatewayUnavailableException) {
            null
        }
        val record = payment?.let {
            records.record(
                RecordPaymentSuccessCommand(
                    provider = it.provider,
                    paymentId = it.paymentKey,
                    reservationId = command.reservationId,
                    amount = it.amount,
                    currency = it.currency,
                    pgOrderNumber = it.orderNumber,
                )
            )
        }
        return result(command, record)
    }

    private fun result(
        command: ApproveReservationPaymentCommand,
        record: ReservationPaymentApprovalResult?,
    ): ReservationPaymentResult {
        val refund = if (record?.status == PaymentStatus.REFUND_REQUIRED) {
            refunds.refund(
                RefundReservationPaymentCommand(
                    command.reservationId,
                    command.userId,
                    command.attemptNumber
                )
            )
        } else null
        return ReservationPaymentResult(command.reservationId, command.attemptNumber, record, refund)
    }
}
