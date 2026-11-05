package me.park.openrush.reservation.adapter.output.persistence.repository

import com.querydsl.jpa.impl.JPAQueryFactory
import jakarta.persistence.EntityManager
import me.park.openrush.reservation.domain.seat.PerformanceSeat
import me.park.openrush.reservation.domain.seat.QPerformanceSeat.performanceSeat
import me.park.openrush.reservation.domain.seat.SeatStatus
import org.springframework.stereotype.Repository
import java.time.Instant

@Repository
class SeatRepositoryCustomImpl(
    private val queryFactory: JPAQueryFactory,
    private val entityManager: EntityManager,
) : SeatRepositoryCustom {
    override fun releaseHeldSeats(reservationId: Long, seatIds: List<Long>, modifiedAt: Instant): Long {
        val updated = queryFactory.update(performanceSeat)
            .set(performanceSeat.status, SeatStatus.AVAILABLE)
            .setNull(performanceSeat.reservationId)
            .set(performanceSeat.modifiedAt, modifiedAt)
            .where(
                performanceSeat.id.`in`(seatIds),
                performanceSeat.reservationId.eq(reservationId),
                performanceSeat.status.eq(SeatStatus.HELD),
            )
            .execute()
        seatIds.forEach { entityManager.detach(entityManager.getReference(PerformanceSeat::class.java, it)) }
        return updated
    }

    override fun findSeats(performanceId: Long, seatIds: List<Long>): List<PerformanceSeat> = queryFactory
        .selectFrom(performanceSeat)
        .where(
            performanceSeat.performance.id.eq(performanceId),
            performanceSeat.id.`in`(seatIds),
        )
        .orderBy(performanceSeat.id.asc())
        .fetch()

    override fun holdAvailableSeats(
        performanceId: Long,
        seatIds: List<Long>,
        reservationId: Long,
        modifiedAt: Instant,
    ): Long {
        val updated = queryFactory.update(performanceSeat)
            .set(performanceSeat.status, SeatStatus.HELD)
            .set(performanceSeat.reservationId, reservationId)
            .set(performanceSeat.modifiedAt, modifiedAt)
            .where(
                performanceSeat.performance.id.eq(performanceId),
                performanceSeat.id.`in`(seatIds),
                performanceSeat.status.eq(SeatStatus.AVAILABLE),
            )
            .execute()

        // 벌크 UPDATE 후 재조회 시 기존의 AVAILABLE 엔티티가 반환되지 않도록 분리한다.
        seatIds.forEach { entityManager.detach(entityManager.getReference(PerformanceSeat::class.java, it)) }
        return updated
    }
}
