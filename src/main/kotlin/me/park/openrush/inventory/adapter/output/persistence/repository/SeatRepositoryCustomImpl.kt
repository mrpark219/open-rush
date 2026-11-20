package me.park.openrush.inventory.adapter.output.persistence.repository

import com.querydsl.jpa.impl.JPAQueryFactory
import jakarta.persistence.EntityManager
import me.park.openrush.inventory.domain.PerformanceSeat
import me.park.openrush.inventory.domain.QPerformanceSeat.performanceSeat
import me.park.openrush.inventory.domain.SeatStatus
import org.springframework.stereotype.Repository
import java.time.Instant

@Repository
class SeatRepositoryCustomImpl(
    private val jpaQueryFactory: JPAQueryFactory,
    private val entityManager: EntityManager,
) : SeatRepositoryCustom {
    override fun confirmHeldSeats(reservationId: Long, seatIds: List<Long>, modifiedAt: Instant): Long {
        val updatedRowCount = jpaQueryFactory.update(performanceSeat)
            .set(performanceSeat.status, SeatStatus.RESERVED)
            .set(performanceSeat.modifiedAt, modifiedAt)
            .where(
                performanceSeat.id.`in`(seatIds),
                performanceSeat.reservationId.eq(reservationId),
                performanceSeat.status.eq(SeatStatus.HELD),
            )
            .execute()
        seatIds.forEach { entityManager.detach(entityManager.getReference(PerformanceSeat::class.java, it)) }
        return updatedRowCount
    }

    override fun releaseHeldSeats(reservationId: Long, seatIds: List<Long>, modifiedAt: Instant): Long {
        val updatedRowCount = jpaQueryFactory.update(performanceSeat)
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
        return updatedRowCount
    }

    override fun findSeats(performanceId: Long, seatIds: List<Long>): List<PerformanceSeat> = jpaQueryFactory
        .selectFrom(performanceSeat)
        .where(
            performanceSeat.performanceId.eq(performanceId),
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
        val updatedRowCount = jpaQueryFactory.update(performanceSeat)
            .set(performanceSeat.status, SeatStatus.HELD)
            .set(performanceSeat.reservationId, reservationId)
            .set(performanceSeat.modifiedAt, modifiedAt)
            .where(
                performanceSeat.performanceId.eq(performanceId),
                performanceSeat.id.`in`(seatIds),
                performanceSeat.status.eq(SeatStatus.AVAILABLE),
            )
            .execute()

        // 벌크 UPDATE 후 재조회 시 기존의 AVAILABLE 엔티티가 반환되지 않도록 분리한다.
        seatIds.forEach { entityManager.detach(entityManager.getReference(PerformanceSeat::class.java, it)) }
        return updatedRowCount
    }
}
