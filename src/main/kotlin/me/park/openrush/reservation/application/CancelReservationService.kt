package me.park.openrush.reservation.application

import me.park.openrush.reservation.application.provided.*
import me.park.openrush.reservation.domain.ReservationStatus
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional

@Service
class CancelReservationService(
    private val cancelReservationTransactions: CancelReservationTransactions,
    private val refundReservationPaymentUseCase: RefundReservationPaymentUseCase,
) : CancelReservationUseCase {
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    override fun cancel(cancelReservationCommand: CancelReservationCommand): CancelReservationResult {
        val attemptNumber = cancelReservationTransactions.cancel(cancelReservationCommand)
        val paymentRefundResult = attemptNumber?.let {
            refundReservationPaymentUseCase.refund(
                RefundReservationPaymentCommand(
                    reservationId = cancelReservationCommand.reservationId,
                    userId = cancelReservationCommand.userId,
                    attemptNumber = it,
                ),
            )
        }
        return CancelReservationResult(
            reservationId = cancelReservationCommand.reservationId,
            status = ReservationStatus.CANCELLED,
            refundStatus = paymentRefundResult?.status,
        )
    }
}
