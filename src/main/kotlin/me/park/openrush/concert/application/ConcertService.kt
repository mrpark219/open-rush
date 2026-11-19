package me.park.openrush.concert.application

import me.park.openrush.concert.application.provided.ConcertDetails
import me.park.openrush.concert.application.provided.GetConcertUseCase
import me.park.openrush.concert.application.provided.GetPerformanceUseCase
import me.park.openrush.concert.application.provided.PerformanceDetails
import me.park.openrush.concert.application.required.ConcertRepositoryPort
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

@Service
@Transactional(readOnly = true)
class ConcertService(private val concerts: ConcertRepositoryPort) : GetPerformanceUseCase, GetConcertUseCase {
    override fun findPerformance(performanceId: Long): PerformanceDetails? =
        concerts.findPerformance(performanceId)?.let {
            PerformanceDetails(
                requireNotNull(it.id),
                requireNotNull(it.concert.id),
                it.bookingOpensAt,
                it.bookingClosesAt,
                it.concert.maxTicketsPerUser
            )
        }

    override fun findConcert(concertId: Long): ConcertDetails? = concerts.findConcert(concertId)?.let {
        ConcertDetails(requireNotNull(it.id), it.maxTicketsPerUser)
    }
}
