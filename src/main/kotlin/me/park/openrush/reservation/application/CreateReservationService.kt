package me.park.openrush.reservation.application

import me.park.openrush.concert.domain.Performance
import me.park.openrush.reservation.application.port.input.CreateReservationCommand
import me.park.openrush.reservation.application.port.input.CreateReservationResult
import me.park.openrush.reservation.application.port.output.AllocatePurchaseQuotaPort
import me.park.openrush.reservation.application.port.output.AllocateQuotaCommand
import me.park.openrush.reservation.application.port.output.HoldPerformanceSeatsPort
import me.park.openrush.reservation.application.port.output.HoldSeatsCommand
import me.park.openrush.reservation.application.port.output.LoadPerformancePort
import me.park.openrush.reservation.application.port.output.LoadPerformanceSeatsPort
import me.park.openrush.reservation.application.port.output.SaveReservationPort
import me.park.openrush.reservation.domain.Reservation
import me.park.openrush.reservation.domain.exception.ReservationRuleViolation
import me.park.openrush.reservation.domain.seat.PerformanceSeat
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import java.time.Clock

@Service
class CreateReservationService(
    private val loadPerformancePort: LoadPerformancePort,
    private val loadPerformanceSeatsPort: LoadPerformanceSeatsPort,
    private val allocatePurchaseQuotaPort: AllocatePurchaseQuotaPort,
    private val saveReservationPort: SaveReservationPort,
    private val holdPerformanceSeatsPort: HoldPerformanceSeatsPort,
    private val clock: Clock,
) {
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    fun create(command: CreateReservationCommand): CreateReservationResult {
        val performance = loadPerformance(command.performanceId)
        val selectedSeats = loadRequestedSeats(command)

        val reservation = Reservation.create(
            performance = performance,
            userId = command.userId,
            seats = selectedSeats,
            now = clock.instant(),
        )

        allocatePurchaseQuotaPort.allocate(AllocateQuotaCommand.from(reservation))

        val savedReservation = saveReservationPort.save(reservation)
        val heldSeats = holdPerformanceSeatsPort.hold(HoldSeatsCommand.from(savedReservation))

        return CreateReservationResult.from(savedReservation, heldSeats)
    }

    private fun loadPerformance(performanceId: Long): Performance =
        loadPerformancePort.findPerformance(performanceId)
            ?: throw ReservationRuleViolation("회차가 존재하지 않습니다.")

    private fun loadRequestedSeats(command: CreateReservationCommand): List<PerformanceSeat> {
        val seats = loadPerformanceSeatsPort.findSeats(command.performanceId, command.seatIds)

        if (seats.size != command.seatIds.size) {
            throw ReservationRuleViolation("요청한 좌석이 해당 회차에 존재하지 않습니다.")
        }

        return seats
    }
}
