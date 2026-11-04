package me.park.openrush.reservation.adapter.output.persistence.repository

import com.querydsl.jpa.impl.JPAQueryFactory
import me.park.openrush.concert.domain.Performance
import me.park.openrush.concert.domain.QConcert.concert
import me.park.openrush.concert.domain.QPerformance.performance
import org.springframework.stereotype.Repository

@Repository
class PerformanceRepositoryCustomImpl(
    private val queryFactory: JPAQueryFactory,
) : PerformanceRepositoryCustom {
    override fun findPerformance(performanceId: Long): Performance? = queryFactory
        .selectFrom(performance)
        .join(performance.concert, concert).fetchJoin()
        .where(performance.id.eq(performanceId))
        .fetchOne()
}
