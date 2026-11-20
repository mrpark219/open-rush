package me.park.openrush.concert.adapter.output.persistence.repository

import com.querydsl.jpa.impl.JPAQueryFactory
import me.park.openrush.concert.domain.Performance
import me.park.openrush.concert.domain.QConcert.concert
import me.park.openrush.concert.domain.QPerformance.performance
import org.springframework.stereotype.Repository

@Repository
class PerformanceRepositoryCustomImpl(
    private val jpaQueryFactory: JPAQueryFactory,
) : PerformanceRepositoryCustom {
    override fun findPerformance(performanceId: Long): Performance? = jpaQueryFactory
        .selectFrom(performance)
        .join(performance.concert, concert).fetchJoin()
        .where(performance.id.eq(performanceId))
        .fetchOne()
}
