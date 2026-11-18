package me.park.openrush.reservation.application

import me.park.openrush.reservation.application.port.input.PrepareReservationPaymentTxUseCase
import me.park.openrush.reservation.application.port.output.LockReservationForPaymentPort
import me.park.openrush.reservation.application.port.output.ReservationPaymentTxPort
import me.park.openrush.reservation.domain.ReservationStatus
import me.park.openrush.reservation.domain.exception.ReservationRuleViolation
import me.park.openrush.reservation.domain.payment.ReservationPaymentTx
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Clock
import java.util.UUID

@Service
class PrepareReservationPaymentTxService(
    private val reservations: LockReservationForPaymentPort,
    private val paymentTransactions: ReservationPaymentTxPort,
    private val clock: Clock,
) : PrepareReservationPaymentTxUseCase {
    @Transactional
    override fun prepare(reservationId: Long, userId: Long, attemptId: UUID): ReservationPaymentTx {
        require(reservationId > 0 && userId > 0) { "예매 ID와 사용자 ID는 양수여야 합니다." }
        val reservation = reservations.lockOwnedReservation(reservationId, userId)
            ?: throw ReservationRuleViolation("결제할 수 있는 예매가 없습니다.")

        val now = clock.reservationTimestamp()
        if (reservation.status != ReservationStatus.HELD || now < reservation.heldAt || now >= reservation.expiresAt) {
            throw ReservationRuleViolation("유효한 선점 예매만 결제할 수 있습니다.")
        }

        paymentTransactions.findByAttempt(reservationId, attemptId)?.let { return it }
        val amount = reservation.totalAmount.amount
        if (amount <= 0) throw ReservationRuleViolation("결제 금액은 양수여야 합니다.")

        val paymentTx = ReservationPaymentTx.prepare(reservationId, attemptId, amount)
        paymentTransactions.save(paymentTx)
        return paymentTx
    }
}
