package me.park.openrush.reservation.application

import me.park.openrush.reservation.application.port.input.PrepareReservationPaymentTxUseCase
import me.park.openrush.reservation.application.port.input.ApproveReservationPaymentCommand
import me.park.openrush.reservation.application.port.input.PreparedReservationPayment
import me.park.openrush.reservation.application.exception.PaymentAttemptConflict
import me.park.openrush.reservation.application.port.output.LockReservationForPaymentPort
import me.park.openrush.reservation.application.port.output.ReservationPaymentTxPort
import me.park.openrush.reservation.domain.ReservationStatus
import me.park.openrush.reservation.domain.exception.ReservationRuleViolation
import me.park.openrush.reservation.domain.payment.ReservationPaymentTx
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Clock

@Service
class PrepareReservationPaymentTxService(
    private val reservations: LockReservationForPaymentPort,
    private val paymentTransactions: ReservationPaymentTxPort,
    private val clock: Clock,
) : PrepareReservationPaymentTxUseCase {
    @Transactional
    override fun prepare(command: ApproveReservationPaymentCommand): PreparedReservationPayment {
        val reservation = reservations.lockOwnedReservation(command.reservationId, command.userId)
            ?: throw ReservationRuleViolation("결제할 수 있는 예매가 없습니다.")
        val now = clock.reservationTimestamp()
        val allowed = reservation.status == ReservationStatus.HELD && now >= reservation.heldAt && now < reservation.expiresAt
        paymentTransactions.findByAttempt(command.reservationId, command.attemptId)?.let {
            if (it.requestedPaymentKey != command.paymentKey) throw PaymentAttemptConflict()
            return PreparedReservationPayment(it, allowed)
        }
        if (!allowed) throw ReservationRuleViolation("유효한 선점 예매만 결제할 수 있습니다.")
        val amount = reservation.totalAmount.amount
        if (amount <= 0) throw ReservationRuleViolation("결제 금액은 양수여야 합니다.")
        val tx = ReservationPaymentTx.prepare(command.reservationId, command.attemptId, amount, command.paymentKey)
        paymentTransactions.save(tx)
        return PreparedReservationPayment(tx, true)
    }
}
