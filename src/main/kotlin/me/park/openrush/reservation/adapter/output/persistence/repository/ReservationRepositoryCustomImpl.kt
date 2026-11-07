package me.park.openrush.reservation.adapter.output.persistence.repository

import com.querydsl.jpa.impl.JPAQueryFactory
import jakarta.persistence.EntityManager
import me.park.openrush.concert.domain.QConcert.concert
import me.park.openrush.concert.domain.QPerformance.performance
import me.park.openrush.reservation.domain.QReservation.reservation
import me.park.openrush.reservation.domain.QReservationItem.reservationItem
import me.park.openrush.reservation.domain.Reservation
import me.park.openrush.reservation.domain.ReservationStatus
import me.park.openrush.reservation.domain.seat.QPerformanceSeat.performanceSeat
import org.springframework.stereotype.Repository
import java.time.Instant

@Repository
class ReservationRepositoryCustomImpl(
    private val queryFactory: JPAQueryFactory,
    private val entityManager: EntityManager,
) : ReservationRepositoryCustom {
    override fun findExpiredReservationIds(now: Instant, afterId: Long, limit: Int): List<Long> = queryFactory
        .select(reservation.id)
        .from(reservation)
        .where(
            reservation.status.eq(ReservationStatus.HELD),
            reservation.expiresAt.loe(now),
            reservation.id.gt(afterId),
        )
        .orderBy(reservation.id.asc())
        .limit(limit.toLong())
        .fetch()

    override fun expireIfHeld(reservationId: Long, now: Instant): Long {
        val updated = queryFactory.update(reservation)
            .set(reservation.status, ReservationStatus.EXPIRED)
            .set(reservation.modifiedAt, now)
            .where(
                reservation.id.eq(reservationId),
                reservation.status.eq(ReservationStatus.HELD),
                reservation.expiresAt.loe(now),
            )
            .execute()
        entityManager.detach(entityManager.getReference(Reservation::class.java, reservationId))
        return updated
    }

    override fun findDetailedById(id: Long): Reservation? = queryFactory
        .selectFrom(reservation)
        .distinct()
        .join(reservation.performance, performance).fetchJoin()
        .join(performance.concert, concert).fetchJoin()
        .leftJoin(reservation.reservationItems, reservationItem).fetchJoin()
        .leftJoin(reservationItem.seat, performanceSeat).fetchJoin()
        .where(reservation.id.eq(id))
        .fetchOne()

    override fun confirmIfHeld(reservationId: Long, userId: Long, now: Instant): Long {
        val updated = queryFactory.update(reservation)
            .set(reservation.status, ReservationStatus.CONFIRMED)
            .set(reservation.confirmedAt, now)
            .set(reservation.modifiedAt, now)
            .where(
                reservation.id.eq(reservationId),
                reservation.userId.eq(userId),
                reservation.status.eq(ReservationStatus.HELD),
                reservation.heldAt.loe(now),
                reservation.expiresAt.gt(now),
            )
            .execute()
        entityManager.detach(entityManager.getReference(Reservation::class.java, reservationId))
        return updated
    }

    override fun cancelIfHeld(reservationId: Long, userId: Long, now: Instant): Long {
        val updated = queryFactory.update(reservation)
            .set(reservation.status, ReservationStatus.CANCELLED)
            .set(reservation.modifiedAt, now)
            .where(
                reservation.id.eq(reservationId),
                reservation.userId.eq(userId),
                reservation.status.eq(ReservationStatus.HELD),
                reservation.heldAt.loe(now),
                reservation.expiresAt.gt(now),
            )
            .execute()
        entityManager.detach(entityManager.getReference(Reservation::class.java, reservationId))
        return updated
    }
}
