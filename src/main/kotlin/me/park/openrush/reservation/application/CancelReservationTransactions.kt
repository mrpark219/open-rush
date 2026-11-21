package me.park.openrush.reservation.application

import me.park.openrush.concert.application.provided.GetPerformanceUseCase
import me.park.openrush.inventory.application.provided.ReleaseReservedSeatsUseCase
import me.park.openrush.quota.application.provided.ReleasePurchaseQuotaUseCase
import me.park.openrush.reservation.application.exception.PaymentRecordConflictException
import me.park.openrush.reservation.application.provided.CancelReservationCommand
import me.park.openrush.reservation.application.required.ReservationRepositoryPort
import me.park.openrush.reservation.application.required.ReservationPaymentRepositoryPort
import me.park.openrush.reservation.domain.ReservationStatus
import me.park.openrush.reservation.domain.exception.ReservationRuleViolationException
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import java.util.UUID

@Service
class CancelReservationTransactions(
    private val reservationRepository: ReservationRepositoryPort,
    private val reservationPaymentRepository: ReservationPaymentRepositoryPort,
    private val getPerformanceUseCase: GetPerformanceUseCase,
    private val releasePurchaseQuotaUseCase: ReleasePurchaseQuotaUseCase,
    private val releaseReservedSeatsUseCase: ReleaseReservedSeatsUseCase,
) {
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    fun cancel(cancelReservationCommand: CancelReservationCommand): UUID? {
        val reservation = reservationRepository.lockOwnedReservation(
            cancelReservationCommand.reservationId,
            cancelReservationCommand.userId,
        ) ?: throw ReservationRuleViolationException("취소할 수 있는 예약이 없습니다.")
        val alreadyCanceled = reservation.status == ReservationStatus.CANCELLED && reservation.confirmedAt != null
        if (!alreadyCanceled && reservation.status != ReservationStatus.CONFIRMED) {
            throw ReservationRuleViolationException("확정된 예약만 취소할 수 있습니다.")
        }

        val attemptNumber = if (reservation.totalAmount > 0) {
            val approvedPaymentTransaction = reservationPaymentRepository.findConfirmedPayment(cancelReservationCommand.reservationId)
                ?: throw PaymentRecordConflictException()
            val cancellationNumber = UUID.nameUUIDFromBytes(
                "refund-${approvedPaymentTransaction.provider}-${approvedPaymentTransaction.paymentId}".toByteArray(Charsets.UTF_8),
            )
            val cancellationPreparation = reservationPaymentRepository.findCancellationPreparation(
                cancelReservationCommand.reservationId, cancellationNumber,
            )
            if (cancellationPreparation != null) {
                if (cancellationPreparation.cancelReason != cancelReservationCommand.cancelReason) {
                    throw PaymentRecordConflictException()
                }
            } else {
                if (alreadyCanceled) throw PaymentRecordConflictException()
                reservationPaymentRepository.save(
                    approvedPaymentTransaction.prepareCancellation(
                        cancellationNumber = cancellationNumber,
                        cancelAmount = approvedPaymentTransaction.amount,
                        cancelReason = cancelReservationCommand.cancelReason,
                    ),
                )
            }
            requireNotNull(approvedPaymentTransaction.attemptNumber)
        } else null

        if (!alreadyCanceled) {
            reservation.cancelConfirmed(cancelReservationCommand.userId)
            // 예약 생성과 같은 한도 → 좌석 순서를 유지한다. 실패하면 준비 이력과 취소도 함께 롤백된다.
            val performance = requireNotNull(getPerformanceUseCase.findPerformance(reservation.performanceId))
            releasePurchaseQuotaUseCase.releaseQuota(reservation.userId, performance.concertId, reservation.quantity)
            releaseReservedSeatsUseCase.releaseReservedSeats(
                requireNotNull(reservation.id), reservation.items.map { reservationItem -> reservationItem.seatId },
            )
        }
        return attemptNumber
    }
}
