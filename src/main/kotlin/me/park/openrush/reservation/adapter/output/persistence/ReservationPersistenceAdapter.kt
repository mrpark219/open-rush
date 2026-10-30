package me.park.openrush.reservation.adapter.output.persistence

import com.querydsl.jpa.impl.JPAQueryFactory
import jakarta.persistence.EntityManager
import me.park.openrush.concert.domain.Performance
import me.park.openrush.concert.domain.QConcert.concert
import me.park.openrush.concert.domain.QPerformance.performance
import me.park.openrush.reservation.application.port.output.LoadPerformancePort
import me.park.openrush.reservation.application.port.output.LoadPerformanceSeatsPort
import me.park.openrush.reservation.application.port.output.SaveReservationPort
import me.park.openrush.reservation.domain.Reservation
import me.park.openrush.reservation.domain.seat.PerformanceSeat
import me.park.openrush.reservation.domain.seat.QPerformanceSeat.performanceSeat
import org.springframework.stereotype.Repository
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional

@Repository
@Transactional(propagation = Propagation.MANDATORY)
class ReservationPersistenceAdapter(
    private val queryFactory: JPAQueryFactory,
    private val entityManager: EntityManager,
) : LoadPerformancePort, LoadPerformanceSeatsPort, SaveReservationPort {
    override fun findPerformance(performanceId: Long): Performance? = queryFactory
        .selectFrom(performance)
        .join(performance.concert, concert).fetchJoin()
        .where(performance.id.eq(performanceId))
        .fetchOne()

    override fun findSeats(performanceId: Long, seatIds: List<Long>): List<PerformanceSeat> = queryFactory
        .selectFrom(performanceSeat)
        .where(
            performanceSeat.performance.id.eq(performanceId),
            performanceSeat.id.`in`(seatIds),
        )
        .orderBy(performanceSeat.id.asc())
        .fetch()

    override fun save(reservation: Reservation): Reservation {
        entityManager.persist(reservation)

        return reservation
    }
}
