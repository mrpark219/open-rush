package me.park.openrush.reservation.adapter.output.persistence

import com.querydsl.jpa.impl.JPAQueryFactory
import jakarta.persistence.EntityManager
import jakarta.persistence.LockModeType
import me.park.openrush.reservation.application.port.output.DuplicateApprovedPayment
import me.park.openrush.reservation.application.port.output.LockReservationForPaymentPort
import me.park.openrush.reservation.application.port.output.ReservationPaymentTxPort
import me.park.openrush.reservation.domain.QReservation.reservation
import me.park.openrush.reservation.domain.Reservation
import me.park.openrush.reservation.domain.payment.QReservationPaymentTx.reservationPaymentTx
import me.park.openrush.reservation.domain.payment.ReservationPaymentTx
import me.park.openrush.reservation.domain.payment.ReservationPaymentTxType
import org.hibernate.exception.ConstraintViolationException
import org.springframework.stereotype.Repository
import java.util.UUID

@Repository
class ReservationPaymentTxPersistenceAdapter(
    private val queryFactory: JPAQueryFactory,
    private val entityManager: EntityManager,
) : ReservationPaymentTxPort, LockReservationForPaymentPort {
    override fun lockReservation(reservationId: Long): Reservation? = reservationForUpdate(reservationId).fetchOne()

    override fun lockOwnedReservation(reservationId: Long, userId: Long): Reservation? =
        reservationForUpdate(reservationId).where(reservation.userId.eq(userId)).fetchOne()

    private fun reservationForUpdate(reservationId: Long) = queryFactory
        .selectFrom(reservation)
        .where(reservation.id.eq(reservationId))
        .setLockMode(LockModeType.PESSIMISTIC_WRITE)

    override fun findByAttempt(reservationId: Long, attemptId: UUID): ReservationPaymentTx? = queryFactory
        .selectFrom(reservationPaymentTx)
        .where(
            reservationPaymentTx.type.eq(ReservationPaymentTxType.APPROVAL_PREPARED),
            reservationPaymentTx.reservationId.eq(reservationId),
            reservationPaymentTx.attemptId.eq(attemptId),
        )
        .fetchOne()

    override fun findPreparedByOrder(provider: String, pgOrderId: String): ReservationPaymentTx? = queryFactory
        .selectFrom(reservationPaymentTx)
        .where(
            reservationPaymentTx.type.eq(ReservationPaymentTxType.APPROVAL_PREPARED),
            reservationPaymentTx.provider.eq(provider),
            reservationPaymentTx.pgOrderId.eq(pgOrderId),
        )
        .fetchOne()

    override fun findApproved(provider: String, paymentId: String): ReservationPaymentTx? = queryFactory
        .selectFrom(reservationPaymentTx)
        .where(
            reservationPaymentTx.type.eq(ReservationPaymentTxType.APPROVED),
            reservationPaymentTx.provider.eq(provider),
            reservationPaymentTx.paymentId.eq(paymentId),
        )
        .fetchOne()

    override fun save(paymentTx: ReservationPaymentTx) {
        try {
            entityManager.persist(paymentTx)
        } catch (exception: RuntimeException) {
            // 서로 다른 예매의 락은 같은 PG 결제 ID의 경합을 직렬화하지 않는다.
            val duplicateApproval = generateSequence<Throwable>(exception) { it.cause }
                .filterIsInstance<ConstraintViolationException>()
                .any { it.constraintName == "uk_reservation_payment_tx_approved_payment" }
            if (duplicateApproval) throw DuplicateApprovedPayment(exception)
            throw exception
        }
    }
}
