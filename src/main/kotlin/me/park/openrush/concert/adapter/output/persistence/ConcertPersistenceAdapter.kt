package me.park.openrush.concert.adapter.output.persistence
import me.park.openrush.concert.adapter.output.persistence.repository.ConcertRepository
import me.park.openrush.concert.adapter.output.persistence.repository.PerformanceRepository
import me.park.openrush.concert.application.required.ConcertRepositoryPort
import me.park.openrush.concert.domain.Concert
import me.park.openrush.concert.domain.Performance
import org.springframework.stereotype.Repository

@Repository
class ConcertPersistenceAdapter(private val performances: PerformanceRepository, private val concerts: ConcertRepository) : ConcertRepositoryPort {
    override fun findPerformance(performanceId: Long): Performance? = performances.findPerformance(performanceId)
    override fun findConcert(concertId: Long): Concert? = concerts.findById(concertId).orElse(null)
}
