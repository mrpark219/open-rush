package me.park.openrush.reservation.adapter.output.persistence.repository

import me.park.openrush.reservation.domain.PaymentStatus
import com.querydsl.jpa.impl.JPAQueryFactory
import me.park.openrush.reservation.domain.ReservationPaymentTransaction
import me.park.openrush.reservation.domain.ReservationPaymentTransactionType
import me.park.openrush.reservation.domain.QReservationPaymentTransaction.reservationPaymentTransaction
import org.springframework.stereotype.Repository
import java.util.UUID

@Repository
class ReservationPaymentRepositoryCustomImpl(private val jpaQueryFactory: JPAQueryFactory) : ReservationPaymentRepositoryCustom {
    override fun findConfirmedPayment(reservationId: Long): ReservationPaymentTransaction? = jpaQueryFactory
        .selectFrom(reservationPaymentTransaction)
        .where(
            reservationPaymentTransaction.reservationId.eq(reservationId),
            reservationPaymentTransaction.type.eq(ReservationPaymentTransactionType.APPROVED),
            reservationPaymentTransaction.status.eq(PaymentStatus.RESERVATION_CONFIRMED),
        )
        .fetchOne()

    override fun findByAttempt(reservationId: Long, attemptNumber: UUID): ReservationPaymentTransaction? = jpaQueryFactory
        .selectFrom(reservationPaymentTransaction)
        .where(
            reservationPaymentTransaction.type.eq(ReservationPaymentTransactionType.APPROVAL_PREPARED),
            reservationPaymentTransaction.reservationId.eq(reservationId),
            reservationPaymentTransaction.attemptNumber.eq(attemptNumber),
        )
        .fetchOne()

    override fun findPreparedByOrder(provider: String, pgOrderNumber: String): ReservationPaymentTransaction? = jpaQueryFactory
        .selectFrom(reservationPaymentTransaction)
        .where(
            reservationPaymentTransaction.type.eq(ReservationPaymentTransactionType.APPROVAL_PREPARED),
            reservationPaymentTransaction.provider.eq(provider),
            reservationPaymentTransaction.pgOrderNumber.eq(pgOrderNumber),
        )
        .fetchOne()

    override fun findCancellationPreparation(reservationId: Long, cancellationNumber: UUID): ReservationPaymentTransaction? =
        jpaQueryFactory.selectFrom(reservationPaymentTransaction)
            .where(
                reservationPaymentTransaction.reservationId.eq(reservationId),
                reservationPaymentTransaction.cancellationNumber.eq(cancellationNumber),
                reservationPaymentTransaction.type.eq(ReservationPaymentTransactionType.CANCEL_PREPARED),
            )
            .fetchOne()

    override fun findCanceled(reservationId: Long, cancellationNumber: UUID): ReservationPaymentTransaction? =
        jpaQueryFactory.selectFrom(reservationPaymentTransaction)
            .where(
                reservationPaymentTransaction.reservationId.eq(reservationId),
                reservationPaymentTransaction.cancellationNumber.eq(cancellationNumber),
                reservationPaymentTransaction.type.eq(ReservationPaymentTransactionType.CANCELED),
            )
            .fetchOne()

    override fun findApproved(provider: String, paymentId: String): ReservationPaymentTransaction? = jpaQueryFactory
        .selectFrom(reservationPaymentTransaction)
        .where(
            reservationPaymentTransaction.type.eq(ReservationPaymentTransactionType.APPROVED),
            reservationPaymentTransaction.provider.eq(provider),
            reservationPaymentTransaction.paymentId.eq(paymentId),
        )
        .fetchOne()

}
