package me.park.openrush.reservation.application

import me.park.openrush.reservation.application.port.input.CancelReservationHoldCommand
import me.park.openrush.reservation.application.port.input.CancelReservationHoldUseCase
import me.park.openrush.reservation.application.port.output.CancelHeldReservationPort
import me.park.openrush.reservation.application.port.output.LoadReservationPort
import me.park.openrush.reservation.domain.exception.ReservationRuleViolation
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Clock

@Service
class CancelReservationHoldService(
    private val loadReservationPort: LoadReservationPort,
    private val cancelHeldReservationPort: CancelHeldReservationPort,
    private val holdResources: ReleaseReservationHoldResources,
    private val clock: Clock,
) : CancelReservationHoldUseCase {
    @Transactional
    override fun cancelHold(command: CancelReservationHoldCommand) {
        val reservation = loadReservationPort.findReservation(command.reservationId)
            ?: throw ReservationRuleViolation("예매가 존재하지 않습니다.")
        val now = clock.reservationTimestamp()
        reservation.validateHoldCancellation(command.userId, now)

        // 상태 전환에 성공한 요청만 자원을 반환한다.
        cancelHeldReservationPort.cancelHeld(command.reservationId, command.userId, now)
        holdResources.release(reservation)
    }
}
