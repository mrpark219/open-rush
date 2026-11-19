package me.park.openrush.reservation.adapter.output.persistence.repository

import com.querydsl.jpa.impl.JPAQueryFactory
import me.park.openrush.reservation.domain.ReservationPaymentTransaction
import me.park.openrush.reservation.domain.ReservationPaymentTransactionType
import me.park.openrush.reservation.domain.QReservationPaymentTransaction.reservationPaymentTransaction
import org.springframework.stereotype.Repository
import java.util.UUID

@Repository
class ReservationPaymentRepositoryCustomImpl(private val queryFactory: JPAQueryFactory) : ReservationPaymentRepositoryCustom {
    override fun findByAttempt(reservationId: Long, attemptNumber: UUID): ReservationPaymentTransaction? = queryFactory
        .selectFrom(reservationPaymentTransaction)
        .where(
            reservationPaymentTransaction.type.eq(ReservationPaymentTransactionType.APPROVAL_PREPARED),
            reservationPaymentTransaction.reservationId.eq(reservationId),
            reservationPaymentTransaction.attemptNumber.eq(attemptNumber),
        )
        .fetchOne()

    override fun findPreparedByOrder(provider: String, pgOrderNumber: String): ReservationPaymentTransaction? = queryFactory
        .selectFrom(reservationPaymentTransaction)
        .where(
            reservationPaymentTransaction.type.eq(ReservationPaymentTransactionType.APPROVAL_PREPARED),
            reservationPaymentTransaction.provider.eq(provider),
            reservationPaymentTransaction.pgOrderNumber.eq(pgOrderNumber),
        )
        .fetchOne()

    override fun findCancellationPreparation(reservationId: Long, cancellationNumber: UUID): ReservationPaymentTransaction? =
        queryFactory.selectFrom(reservationPaymentTransaction)
            .where(
                reservationPaymentTransaction.reservationId.eq(reservationId),
                reservationPaymentTransaction.cancellationNumber.eq(cancellationNumber),
                reservationPaymentTransaction.type.eq(ReservationPaymentTransactionType.CANCEL_PREPARED),
            )
            .fetchOne()

    override fun findCanceled(reservationId: Long, cancellationNumber: UUID): ReservationPaymentTransaction? =
        queryFactory.selectFrom(reservationPaymentTransaction)
            .where(
                reservationPaymentTransaction.reservationId.eq(reservationId),
                reservationPaymentTransaction.cancellationNumber.eq(cancellationNumber),
                reservationPaymentTransaction.type.eq(ReservationPaymentTransactionType.CANCELED),
            )
            .fetchOne()

    override fun findApproved(provider: String, paymentId: String): ReservationPaymentTransaction? = queryFactory
        .selectFrom(reservationPaymentTransaction)
        .where(
            reservationPaymentTransaction.type.eq(ReservationPaymentTransactionType.APPROVED),
            reservationPaymentTransaction.provider.eq(provider),
            reservationPaymentTransaction.paymentId.eq(paymentId),
        )
        .fetchOne()

}
