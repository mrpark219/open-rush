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

@Service
class ConfirmReservationService(
    private val loadReservationPort: LoadReservationPort,
    private val confirmHeldReservationPort: ConfirmHeldReservationPort,
    private val confirmPerformanceSeatsPort: ConfirmPerformanceSeatsPort,
    private val clock: Clock,
) : ConfirmReservationUseCase {
    // 상태 변경 전 정책 거절은 호출자가 같은 트랜잭션에 결제 결과를 기록할 수 있게 한다.
    @Transactional(noRollbackFor = [ReservationRuleViolation::class])
    override fun confirm(command: ConfirmReservationCommand) {
        val reservation = loadReservationPort.findReservation(command.reservationId)
            ?: throw ReservationRuleViolation("예매가 존재하지 않습니다.")
        val now = clock.reservationTimestamp()
        reservation.validateConfirmation(command.userId, now)

        confirmHeldReservationPort.confirmHeld(command.reservationId, command.userId, now)
        confirmPerformanceSeatsPort.confirmSeats(
            command.reservationId,
            reservation.items.map { requireNotNull(it.seat.id) },
            now,
        )
    }
}
