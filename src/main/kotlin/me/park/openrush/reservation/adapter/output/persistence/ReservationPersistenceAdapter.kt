package me.park.openrush.reservation.adapter.output.persistence

import java.time.Instant
import me.park.openrush.reservation.application.port.output.ExpireHeldReservationPort
import me.park.openrush.reservation.application.port.output.FindExpiredReservationHoldsPort
import org.springframework.transaction.annotation.Transactional
import me.park.openrush.concert.domain.Performance
import me.park.openrush.reservation.adapter.output.persistence.repository.PerformanceRepository
import me.park.openrush.reservation.adapter.output.persistence.repository.ReservationRepository
import me.park.openrush.reservation.adapter.output.persistence.repository.SeatRepository
import me.park.openrush.reservation.application.port.output.CancelHeldReservationPort
import me.park.openrush.reservation.application.port.output.ConfirmHeldReservationPort
import me.park.openrush.reservation.application.port.output.LoadPerformancePort
import me.park.openrush.reservation.application.port.output.LoadPerformanceSeatsPort
import me.park.openrush.reservation.application.port.output.LoadReservationPort
import me.park.openrush.reservation.application.port.output.SaveReservationPort
import me.park.openrush.reservation.domain.Reservation
import me.park.openrush.reservation.domain.exception.ReservationRuleViolation
import me.park.openrush.reservation.domain.seat.PerformanceSeat
import org.springframework.stereotype.Component

@Component
class ReservationPersistenceAdapter(
    private val performanceRepository: PerformanceRepository,
    private val seatRepository: SeatRepository,
    private val reservationRepository: ReservationRepository,
) : LoadPerformancePort, LoadPerformanceSeatsPort, SaveReservationPort, LoadReservationPort, CancelHeldReservationPort,
    ExpireHeldReservationPort, FindExpiredReservationHoldsPort, ConfirmHeldReservationPort {
    @Transactional(readOnly = true)
    override fun findExpiredReservationIds(now: Instant, afterId: Long, limit: Int): List<Long> {
        require(afterId >= 0 && limit > 0)
        return reservationRepository.findExpiredReservationIds(now, afterId, limit)
    }

    override fun expireHeld(reservationId: Long, now: Instant): Boolean =
        reservationRepository.expireIfHeld(reservationId, now) == 1L

    override fun findReservation(reservationId: Long): Reservation? =
        reservationRepository.findDetailedById(reservationId)

    override fun confirmHeld(reservationId: Long, userId: Long, now: Instant) {
        if (reservationRepository.confirmIfHeld(reservationId, userId, now) != 1L) {
            throw ReservationRuleViolation("확정 가능한 선점 예매가 아닙니다.")
        }
    }

    override fun cancelHeld(reservationId: Long, userId: Long, now: Instant) {
        if (reservationRepository.cancelIfHeld(reservationId, userId, now) != 1L) {
            throw ReservationRuleViolation("취소 가능한 선점 예매가 아닙니다.")
        }
    }

    override fun findPerformance(performanceId: Long): Performance? =
        performanceRepository.findPerformance(performanceId)

    override fun findSeats(performanceId: Long, seatIds: List<Long>): List<PerformanceSeat> =
        seatRepository.findSeats(performanceId, seatIds)

    override fun save(reservation: Reservation): Reservation = reservationRepository.save(reservation)
}
