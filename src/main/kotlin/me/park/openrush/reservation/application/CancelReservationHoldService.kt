package me.park.openrush.reservation.application

import me.park.openrush.reservation.application.port.input.CancelReservationHoldCommand
import me.park.openrush.reservation.application.port.input.CancelReservationHoldUseCase
import me.park.openrush.reservation.application.port.output.CancelHeldReservationPort
import me.park.openrush.reservation.application.port.output.LoadReservationPort
import me.park.openrush.reservation.application.port.output.ReleasePerformanceSeatsPort
import me.park.openrush.reservation.application.port.output.ReleasePurchaseQuotaPort
import me.park.openrush.reservation.domain.exception.ReservationRuleViolation
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Clock

@Service
class CancelReservationHoldService(
    private val loadReservationPort: LoadReservationPort,
    private val cancelHeldReservationPort: CancelHeldReservationPort,
    private val releasePurchaseQuotaPort: ReleasePurchaseQuotaPort,
    private val releasePerformanceSeatsPort: ReleasePerformanceSeatsPort,
    private val clock: Clock,
) : CancelReservationHoldUseCase {
    @Transactional
    override fun cancelHold(command: CancelReservationHoldCommand) {
        val reservation = loadReservationPort.findReservation(command.reservationId)
            ?: throw ReservationRuleViolation("예매가 존재하지 않습니다.")
        val now = clock.instant()
        reservation.validateHoldCancellation(command.userId, now)

        // 상태 전환에 성공한 요청만 반환한다. 생성과 동일하게 한도 → 좌석 순서로 갱신한다.
        cancelHeldReservationPort.cancelHeld(command.reservationId, command.userId, now)
        releasePurchaseQuotaPort.releaseQuota(
            reservation.userId,
            requireNotNull(reservation.performance.concert.id),
            reservation.quantity,
        )
        releasePerformanceSeatsPort.releaseSeats(
            command.reservationId,
            reservation.items.map { requireNotNull(it.seat.id) },
        )
    }
}
