package me.park.openrush.payment.application

import me.park.openrush.payment.application.port.input.RecordPaymentSuccessUseCase
import me.park.openrush.payment.application.port.output.PaymentRecordPort
import me.park.openrush.payment.domain.PaymentRecord
import me.park.openrush.payment.domain.PaymentStatus
import me.park.openrush.payment.domain.RefundReason
import me.park.openrush.payment.domain.VerifiedPayment
import me.park.openrush.reservation.application.port.input.ConfirmReservationCommand
import me.park.openrush.reservation.application.port.input.ConfirmReservationUseCase
import me.park.openrush.reservation.application.port.output.LoadReservationPort
import me.park.openrush.reservation.domain.exception.ReservationRuleViolation
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

@Service
class RecordPaymentSuccessService(
    private val paymentRecordPort: PaymentRecordPort,
    private val loadReservationPort: LoadReservationPort,
    private val confirmReservationUseCase: ConfirmReservationUseCase,
) : RecordPaymentSuccessUseCase {
    @Transactional
    override fun record(payment: VerifiedPayment): PaymentRecord {
        val reservation = requireNotNull(loadReservationPort.findReservation(payment.reservationId)) {
            "결제에 연결된 예매가 존재하지 않습니다."
        }
        if (!paymentRecordPort.claim(payment)) {
            val existing = checkNotNull(paymentRecordPort.find(payment.provider, payment.paymentId))
            require(existing.payment == payment) { "기록된 결제와 재전달된 결제 정보가 다릅니다." }
            return existing
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
        paymentRecordPort.complete(record)
        return record
    }
}
