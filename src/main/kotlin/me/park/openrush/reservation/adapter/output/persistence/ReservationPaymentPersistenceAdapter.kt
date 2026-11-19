package me.park.openrush.reservation.adapter.output.persistence

import me.park.openrush.reservation.adapter.output.persistence.repository.ReservationPaymentRepository
import me.park.openrush.reservation.application.exception.DuplicateApprovedPaymentException
import me.park.openrush.reservation.application.required.ReservationPaymentRepositoryPort
import me.park.openrush.reservation.domain.ReservationPaymentTransaction
import org.hibernate.exception.ConstraintViolationException
import org.springframework.stereotype.Repository
import java.util.*

@Repository
class ReservationPaymentPersistenceAdapter(
    private val payments: ReservationPaymentRepository,
) : ReservationPaymentRepositoryPort {
    override fun findByAttempt(reservationId: Long, attemptNumber: UUID): ReservationPaymentTransaction? =
        payments.findByAttempt(reservationId, attemptNumber)

    override fun findPreparedByOrder(provider: String, pgOrderNumber: String): ReservationPaymentTransaction? =
        payments.findPreparedByOrder(provider, pgOrderNumber)

    override fun findCancellationPreparation(reservationId: Long, cancellationNumber: UUID): ReservationPaymentTransaction? =
        payments.findCancellationPreparation(reservationId, cancellationNumber)

    override fun findCanceled(reservationId: Long, cancellationNumber: UUID): ReservationPaymentTransaction? =
        payments.findCanceled(reservationId, cancellationNumber)

    override fun findApproved(provider: String, paymentId: String): ReservationPaymentTransaction? =
        payments.findApproved(provider, paymentId)

    override fun save(paymentTx: ReservationPaymentTransaction) {
        try {
            payments.save(paymentTx)
        } catch (exception: RuntimeException) {
            // 서로 다른 예매의 락은 같은 PG 결제 ID의 경합을 직렬화하지 않는다.
            val duplicateApproval = generateSequence<Throwable>(exception) { it.cause }
                .filterIsInstance<ConstraintViolationException>()
                .any { it.constraintName == "uk_reservation_payment_tx_approved_payment" }
            if (duplicateApproval) throw DuplicateApprovedPaymentException(exception)
            throw exception
        }
    }
}
