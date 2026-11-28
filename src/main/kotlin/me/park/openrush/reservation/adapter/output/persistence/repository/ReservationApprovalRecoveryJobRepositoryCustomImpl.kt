package me.park.openrush.reservation.adapter.output.persistence.repository

import com.querydsl.jpa.impl.JPAQueryFactory
import jakarta.persistence.LockModeType
import me.park.openrush.reservation.domain.ApprovalRecoveryStatus
import me.park.openrush.reservation.domain.QReservationApprovalRecoveryJob.reservationApprovalRecoveryJob
import me.park.openrush.reservation.domain.ReservationApprovalRecoveryJob
import org.hibernate.Timeouts
import org.hibernate.jpa.SpecHints
import org.springframework.stereotype.Repository
import java.time.Instant

@Repository
class ReservationApprovalRecoveryJobRepositoryCustomImpl(
    private val jpaQueryFactory: JPAQueryFactory,
) : ReservationApprovalRecoveryJobRepositoryCustom {
    override fun lockNextDue(now: Instant): ReservationApprovalRecoveryJob? = jpaQueryFactory
        .selectFrom(reservationApprovalRecoveryJob)
        .where(
            reservationApprovalRecoveryJob.status.eq(ApprovalRecoveryStatus.PENDING),
            reservationApprovalRecoveryJob.nextLookupAt.loe(now),
            reservationApprovalRecoveryJob.leaseExpiresAt.isNull
                .or(reservationApprovalRecoveryJob.leaseExpiresAt.loe(now)),
        )
        .orderBy(reservationApprovalRecoveryJob.nextLookupAt.asc(), reservationApprovalRecoveryJob.id.asc())
        .setLockMode(LockModeType.PESSIMISTIC_WRITE)
        .setHint(SpecHints.HINT_SPEC_LOCK_TIMEOUT, Timeouts.SKIP_LOCKED_MILLI)
        .fetchFirst()

    override fun lockByPreparationId(paymentPreparationId: Long): ReservationApprovalRecoveryJob? = jpaQueryFactory
        .selectFrom(reservationApprovalRecoveryJob)
        .where(reservationApprovalRecoveryJob.paymentPreparationId.eq(paymentPreparationId))
        .setLockMode(LockModeType.PESSIMISTIC_WRITE)
        .fetchOne()
}
