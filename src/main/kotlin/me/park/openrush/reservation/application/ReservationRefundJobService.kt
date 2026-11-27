package me.park.openrush.reservation.application

import me.park.openrush.reservation.application.required.ReservationPaymentRepositoryPort
import me.park.openrush.reservation.domain.ReservationPaymentTransaction
import me.park.openrush.reservation.domain.ReservationPaymentTransactionType
import me.park.openrush.reservation.domain.ReservationRefundJob
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import java.time.Clock

/** 호출자가 예약을 잠근 트랜잭션 안에서 환불 작업을 등록한다. */
@Service
@Transactional(propagation = Propagation.MANDATORY)
class ReservationRefundJobService(
    private val reservationPaymentRepository: ReservationPaymentRepositoryPort,
    private val clock: Clock,
) {
    fun enqueue(approvedPaymentTransaction: ReservationPaymentTransaction): ReservationRefundJob {
        require(approvedPaymentTransaction.type == ReservationPaymentTransactionType.APPROVED)
        require(approvedPaymentTransaction.amount > 0)

        val approvedPaymentTransactionId = requireNotNull(approvedPaymentTransaction.id)
        val existingJob = reservationPaymentRepository.findRefundJob(approvedPaymentTransactionId)
        if (existingJob != null) {
            return existingJob
        }

        val refundJob = ReservationRefundJob(approvedPaymentTransactionId, clock.instant())
        val existingCancellation = reservationPaymentRepository.findCanceled(
            approvedPaymentTransaction.reservationId,
            approvedPaymentTransaction.refundCancellationNumber(),
        )
        if (existingCancellation != null && existingCancellation.amount == approvedPaymentTransaction.amount) {
            refundJob.complete(requireNotNull(existingCancellation.recordedAt))
        }

        reservationPaymentRepository.saveRefundJob(refundJob)

        return refundJob
    }
}
