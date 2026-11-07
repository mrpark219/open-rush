package me.park.openrush.reservation.application

import me.park.openrush.reservation.application.port.input.ConfirmReservationCommand
import me.park.openrush.reservation.application.port.input.ConfirmReservationUseCase
import me.park.openrush.reservation.application.port.output.ConfirmHeldReservationPort
import me.park.openrush.reservation.application.port.output.ConfirmPerformanceSeatsPort
import me.park.openrush.reservation.application.port.output.LoadReservationPort
import me.park.openrush.reservation.domain.exception.ReservationRuleViolation
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Clock
import java.time.temporal.ChronoUnit

@Service
class ConfirmReservationService(
    private val loadReservationPort: LoadReservationPort,
    private val confirmHeldReservationPort: ConfirmHeldReservationPort,
    private val confirmPerformanceSeatsPort: ConfirmPerformanceSeatsPort,
    private val clock: Clock,
) : ConfirmReservationUseCase {
    @Transactional
    override fun confirm(command: ConfirmReservationCommand) {
        val reservation = loadReservationPort.findReservation(command.reservationId)
            ?: throw ReservationRuleViolation("예매가 존재하지 않습니다.")
        // DB 정밀도에 맞춰 기한 직전의 시각이 만료 시각으로 반올림되지 않게 한다.
        val now = clock.instant().truncatedTo(ChronoUnit.MICROS)
        reservation.validateConfirmation(command.userId, now)

        confirmHeldReservationPort.confirmHeld(command.reservationId, command.userId, now)
        confirmPerformanceSeatsPort.confirmSeats(
            command.reservationId,
            reservation.items.map { requireNotNull(it.seat.id) },
            now,
        )
    }
}
