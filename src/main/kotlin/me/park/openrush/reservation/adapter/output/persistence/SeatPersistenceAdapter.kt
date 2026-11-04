package me.park.openrush.reservation.adapter.output.persistence

import me.park.openrush.reservation.adapter.output.persistence.repository.SeatRepository
import me.park.openrush.reservation.application.port.output.HoldPerformanceSeatsPort
import me.park.openrush.reservation.application.port.output.HoldSeatsCommand
import me.park.openrush.reservation.application.port.output.HoldSeatsResult
import me.park.openrush.reservation.domain.exception.ReservationRuleViolation
import org.springframework.stereotype.Component
import java.time.Clock

@Component
class SeatPersistenceAdapter(
    private val seatRepository: SeatRepository,
    private val clock: Clock,
) : HoldPerformanceSeatsPort {
    override fun hold(command: HoldSeatsCommand): HoldSeatsResult {
        val updated = seatRepository.holdAvailableSeats(
            performanceId = command.performanceId,
            seatIds = command.seatIds,
            reservationId = command.reservationId,
            modifiedAt = clock.instant(),
        )

        if (updated != command.seatIds.size.toLong()) {
            // 일부 좌석만 갱신됐어도 예매와 구매 한도를 포함해 전부 롤백한다.
            throw ReservationRuleViolation("요청한 좌석이 없거나 이미 점유되었습니다.")
        }

        return HoldSeatsResult(seatIds = command.seatIds.sorted())
    }
}
