package me.park.openrush.reservation.adapter.output.persistence

import java.time.Clock
import me.park.openrush.reservation.adapter.output.persistence.repository.SeatRepository
import me.park.openrush.reservation.application.port.output.HoldPerformanceSeatsPort
import me.park.openrush.reservation.application.port.output.HoldSeatsCommand
import me.park.openrush.reservation.application.port.output.HoldSeatsResult
import me.park.openrush.reservation.application.port.output.ReleasePerformanceSeatsPort
import me.park.openrush.reservation.domain.exception.ReservationRuleViolation
import org.springframework.stereotype.Component

@Component
class SeatPersistenceAdapter(
    private val seatRepository: SeatRepository,
    private val clock: Clock,
) : HoldPerformanceSeatsPort, ReleasePerformanceSeatsPort {
    override fun releaseSeats(reservationId: Long, seatIds: List<Long>) {
        require(reservationId > 0 && seatIds.isNotEmpty() && seatIds.all { it > 0 })
        require(seatIds.distinct().size == seatIds.size)
        check(seatRepository.releaseHeldSeats(reservationId, seatIds, clock.instant()) == seatIds.size.toLong()) {
            "예매가 선점한 좌석을 모두 반환하지 못했습니다."
        }
    }

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
