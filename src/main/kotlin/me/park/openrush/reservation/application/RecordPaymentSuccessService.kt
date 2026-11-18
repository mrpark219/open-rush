package me.park.openrush.reservation.application

import me.park.openrush.reservation.application.exception.PaymentRecordConflict
import me.park.openrush.reservation.application.port.input.ConfirmReservationCommand
import me.park.openrush.reservation.application.port.input.ConfirmReservationUseCase
import me.park.openrush.reservation.application.port.input.RecordPaymentSuccessUseCase
import me.park.openrush.reservation.application.port.output.DuplicateApprovedPayment
import me.park.openrush.reservation.application.port.output.LockReservationForPaymentPort
import me.park.openrush.reservation.application.port.output.ReservationPaymentTxPort
import me.park.openrush.reservation.domain.exception.ReservationRuleViolation
import me.park.openrush.reservation.domain.payment.PaymentRecord
import me.park.openrush.reservation.domain.payment.PaymentStatus
import me.park.openrush.reservation.domain.payment.RefundReason
import me.park.openrush.reservation.domain.payment.ReservationPaymentTx
import me.park.openrush.reservation.domain.payment.VerifiedPayment
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

@Service
class RecordPaymentSuccessService(
    private val reservations: LockReservationForPaymentPort,
    private val paymentTransactions: ReservationPaymentTxPort,
    private val confirmReservationUseCase: ConfirmReservationUseCase,
) : RecordPaymentSuccessUseCase {
    @Transactional
    override fun record(payment: VerifiedPayment): PaymentRecord {
        val reservation = requireNotNull(reservations.lockReservation(payment.reservationId)) {
            "결제에 연결된 예매가 존재하지 않습니다."
        }
        paymentTransactions.findApproved(payment.provider, payment.paymentId)?.toPaymentRecord()?.let { existing ->
            if (existing.payment != payment) throw PaymentRecordConflict()
            return existing
        }

        val preparation = payment.pgOrderId?.let { orderId ->
            paymentTransactions.findPreparedByOrder(payment.provider, orderId)
                ?.takeIf { it.reservationId == payment.reservationId }
                ?: throw IllegalArgumentException("결제의 PG 주문이 예매와 일치하지 않습니다.")
        }

        val record = if (payment.currency != "KRW" || payment.amount != reservation.totalAmount.amount) {
            PaymentRecord(payment, PaymentStatus.REFUND_REQUIRED, RefundReason.PAYMENT_MISMATCH)
        } else {
            try {
                confirmReservationUseCase.confirm(ConfirmReservationCommand(reservation.userId, payment.reservationId))
                PaymentRecord(payment, PaymentStatus.RESERVATION_CONFIRMED)
            } catch (_: ReservationRuleViolation) {
                PaymentRecord(payment, PaymentStatus.REFUND_REQUIRED, RefundReason.RESERVATION_UNAVAILABLE)
            }
        }
        try {
            paymentTransactions.save(ReservationPaymentTx.approved(record, preparation))
        } catch (exception: DuplicateApprovedPayment) {
            throw PaymentRecordConflict(exception)
        }
        return record
    }
}
