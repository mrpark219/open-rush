package me.park.openrush.reservation.adapter.output.persistence.repository

import com.querydsl.jpa.impl.JPAQueryFactory
import jakarta.persistence.LockModeType
import me.park.openrush.reservation.domain.QReservationRefundJob.reservationRefundJob
import me.park.openrush.reservation.domain.ReservationRefundJob
import me.park.openrush.reservation.domain.ReservationRefundJobStatus
import org.hibernate.Timeouts
import org.hibernate.jpa.SpecHints
import org.springframework.stereotype.Repository
import java.time.Instant

@Repository
class ReservationRefundJobRepositoryCustomImpl(
    private val jpaQueryFactory: JPAQueryFactory,
) : ReservationRefundJobRepositoryCustom {
    override fun lockNextDue(now: Instant): ReservationRefundJob? = dueJobs(now)
        .orderBy(reservationRefundJob.nextAttemptAt.asc(), reservationRefundJob.id.asc())
        .fetchFirst()

    override fun lockDue(approvedPaymentTransactionId: Long, now: Instant): ReservationRefundJob? = dueJobs(now)
        .where(reservationRefundJob.approvedPaymentTransactionId.eq(approvedPaymentTransactionId))
        .fetchOne()

    override fun lockById(refundJobId: Long): ReservationRefundJob? = jpaQueryFactory
        .selectFrom(reservationRefundJob)
        .where(reservationRefundJob.id.eq(refundJobId))
        .setLockMode(LockModeType.PESSIMISTIC_WRITE)
        .fetchOne()

    private fun dueJobs(now: Instant) = jpaQueryFactory
        .selectFrom(reservationRefundJob)
        .where(
            reservationRefundJob.status.eq(ReservationRefundJobStatus.PENDING),
            reservationRefundJob.nextAttemptAt.loe(now),
            reservationRefundJob.leaseExpiresAt.isNull.or(reservationRefundJob.leaseExpiresAt.loe(now)),
        )
        .setLockMode(LockModeType.PESSIMISTIC_WRITE)
        // Hibernate가 PostgreSQL의 FOR UPDATE SKIP LOCKED를 생성한다.
        .setHint(SpecHints.HINT_SPEC_LOCK_TIMEOUT, Timeouts.SKIP_LOCKED_MILLI)
}
