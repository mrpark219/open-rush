package me.park.openrush.reservation.adapter.output.persistence

import me.park.openrush.concert.domain.Performance
import me.park.openrush.reservation.adapter.output.persistence.repository.PerformanceRepository
import me.park.openrush.reservation.adapter.output.persistence.repository.ReservationRepository
import me.park.openrush.reservation.adapter.output.persistence.repository.SeatRepository
import me.park.openrush.reservation.application.port.output.LoadPerformancePort
import me.park.openrush.reservation.application.port.output.LoadPerformanceSeatsPort
import me.park.openrush.reservation.application.port.output.SaveReservationPort
import me.park.openrush.reservation.domain.Reservation
import me.park.openrush.reservation.domain.seat.PerformanceSeat
import org.springframework.stereotype.Component

@Component
class ReservationPersistenceAdapter(
    private val performanceRepository: PerformanceRepository,
    private val seatRepository: SeatRepository,
    private val reservationRepository: ReservationRepository,
) : LoadPerformancePort, LoadPerformanceSeatsPort, SaveReservationPort {
    override fun findPerformance(performanceId: Long): Performance? =
        performanceRepository.findPerformance(performanceId)

    override fun findSeats(performanceId: Long, seatIds: List<Long>): List<PerformanceSeat> =
        seatRepository.findSeats(performanceId, seatIds)

    override fun save(reservation: Reservation): Reservation = reservationRepository.save(reservation)
}
