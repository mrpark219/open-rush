package me.park.openrush.reservation.application

import me.park.openrush.reservation.application.exception.PaymentRecordConflict
import me.park.openrush.reservation.application.port.input.ApproveReservationPaymentCommand
import me.park.openrush.reservation.application.port.input.ApproveReservationPaymentUseCase
import me.park.openrush.reservation.application.port.input.PrepareReservationPaymentTxUseCase
import me.park.openrush.reservation.application.port.input.RecordPaymentSuccessUseCase
import me.park.openrush.reservation.application.port.input.ReservationPaymentResult
import me.park.openrush.reservation.application.port.output.PaymentGatewayUnavailable
import me.park.openrush.reservation.application.port.output.ReservationPaymentGateway
import me.park.openrush.reservation.application.port.output.ReservationPaymentTxPort
import me.park.openrush.reservation.domain.payment.VerifiedPayment
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional

@Service
class ApproveReservationPaymentService(
    private val preparations: PrepareReservationPaymentTxUseCase,
    private val transactions: ReservationPaymentTxPort,
    private val gateway: ReservationPaymentGateway,
    private val records: RecordPaymentSuccessUseCase,
) : ApproveReservationPaymentUseCase {
    // 준비와 결과 저장만 각각 트랜잭션을 사용하며 PG 호출 중에는 잠금을 유지하지 않는다.
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    override fun approve(command: ApproveReservationPaymentCommand): ReservationPaymentResult {
        val prepared = preparations.prepare(command)
        val tx = prepared.transaction
        transactions.findApproved(tx.provider, command.paymentKey)?.let {
            if (it.reservationId != command.reservationId || it.pgOrderId != tx.pgOrderId) {
                throw PaymentRecordConflict()
            }
            return ReservationPaymentResult(command.reservationId, command.attemptId, it.toPaymentRecord())
        }

        val payment: VerifiedPayment? = try {
            gateway.findByOrder(tx) ?: if (prepared.approvalAllowed) {
                try {
                    gateway.confirm(tx)
                } catch (_: PaymentGatewayUnavailable) {
                    // 승인 응답 유실은 실패로 단정하지 않고 같은 주문으로 조회한다.
                    gateway.findByOrder(tx)
                }
            } else null
        } catch (_: PaymentGatewayUnavailable) {
            null
        }
        return ReservationPaymentResult(command.reservationId, command.attemptId, payment?.let(records::record))
    }
}
