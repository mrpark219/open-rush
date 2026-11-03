package me.park.openrush.reservation.adapter.output.persistence

import com.querydsl.jpa.impl.JPAQueryFactory
import jakarta.persistence.EntityManager
import me.park.openrush.reservation.application.port.output.HoldPerformanceSeatsPort
import me.park.openrush.reservation.application.port.output.HoldSeatsCommand
import me.park.openrush.reservation.application.port.output.HoldSeatsResult
import me.park.openrush.reservation.domain.exception.ReservationRuleViolation
import me.park.openrush.reservation.domain.seat.PerformanceSeat
import me.park.openrush.reservation.domain.seat.SeatStatus
import me.park.openrush.reservation.domain.seat.QPerformanceSeat.performanceSeat
import org.springframework.stereotype.Repository
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import java.time.Instant

@Repository
@Transactional(propagation = Propagation.MANDATORY)
class ConditionalUpdateSeatAdapter(
    private val queryFactory: JPAQueryFactory,
    private val entityManager: EntityManager,
) : HoldPerformanceSeatsPort {
    @Throws(ReservationRuleViolation::class)
    override fun hold(command: HoldSeatsCommand): HoldSeatsResult {
        val seats = findSeats(command)

        if (seats.size != command.seatIds.size) {
            throw ReservationRuleViolation("요청한 좌석이 해당 회차에 존재하지 않습니다.")
        }

        entityManager.flush()
        seats.forEach(entityManager::detach)

        // 겹치는 다중 좌석 요청도 같은 순서로 갱신해 교착을 방지한다.
        val modifiedAt = Instant.now()
        seats.forEach { seat ->
            val updated = queryFactory.update(performanceSeat)
                .set(performanceSeat.status, SeatStatus.HELD)
                .set(performanceSeat.reservationId, command.reservationId)
                .set(performanceSeat.modifiedAt, modifiedAt)
                .where(
                    performanceSeat.id.eq(requireNotNull(seat.id)),
                    performanceSeat.performance.id.eq(command.performanceId),
                    performanceSeat.status.eq(SeatStatus.AVAILABLE),
                )
                .execute()

            if (updated != 1L) {
                // 호출자의 트랜잭션을 롤백해 앞서 선점한 좌석과 구매 한도도 되돌린다.
                throw ReservationRuleViolation("이미 점유된 좌석입니다.")
            }
        }

        return HoldSeatsResult(seatIds = seats.map { requireNotNull(it.id) })
    }

    private fun findSeats(command: HoldSeatsCommand): List<PerformanceSeat> = queryFactory
        .selectFrom(performanceSeat)
        .where(
            performanceSeat.performance.id.eq(command.performanceId),
            performanceSeat.id.`in`(command.seatIds),
        )
        .orderBy(performanceSeat.id.asc())
        .fetch()
}
