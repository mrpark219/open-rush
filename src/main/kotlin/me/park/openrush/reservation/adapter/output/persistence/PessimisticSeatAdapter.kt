package me.park.openrush.reservation.adapter.output.persistence

import com.querydsl.jpa.impl.JPAQueryFactory
import jakarta.persistence.EntityManager
import jakarta.persistence.LockModeType
import me.park.openrush.reservation.application.port.output.HoldPerformanceSeatsPort
import me.park.openrush.reservation.application.port.output.HoldSeatsCommand
import me.park.openrush.reservation.application.port.output.HoldSeatsResult
import me.park.openrush.reservation.domain.exception.ReservationRuleViolation
import me.park.openrush.reservation.domain.seat.PerformanceSeat
import me.park.openrush.reservation.domain.seat.QPerformanceSeat.performanceSeat
import org.springframework.stereotype.Repository
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional

@Repository
@Transactional(propagation = Propagation.MANDATORY)
class PessimisticSeatAdapter(
    private val queryFactory: JPAQueryFactory,
    private val entityManager: EntityManager,
) : HoldPerformanceSeatsPort {
    @Throws(ReservationRuleViolation::class)
    override fun hold(command: HoldSeatsCommand): HoldSeatsResult {
        val seats = findSeatsForUpdate(command)

        if (seats.size != command.seatIds.size) {
            throw ReservationRuleViolation("요청한 좌석이 해당 회차에 존재하지 않습니다.")
        }

        seats.forEach(entityManager::refresh)
        seats.forEach { it.hold(command.reservationId) }
        entityManager.flush()

        return HoldSeatsResult(seatIds = seats.map { requireNotNull(it.id) })
    }

    private fun findSeatsForUpdate(command: HoldSeatsCommand): List<PerformanceSeat> = queryFactory
        .selectFrom(performanceSeat)
        .where(
            performanceSeat.performance.id.eq(command.performanceId),
            performanceSeat.id.`in`(command.seatIds),
        )
        .orderBy(performanceSeat.id.asc())
        .setLockMode(LockModeType.PESSIMISTIC_WRITE)
        .fetch()
}
