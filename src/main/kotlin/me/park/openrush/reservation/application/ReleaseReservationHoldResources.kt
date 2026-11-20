package me.park.openrush.reservation.application

import me.park.openrush.concert.application.provided.GetPerformanceUseCase
import me.park.openrush.inventory.application.provided.ReleaseSeatsUseCase
import me.park.openrush.quota.application.provided.ReleasePurchaseQuotaUseCase
import me.park.openrush.reservation.domain.Reservation
import org.springframework.stereotype.Component

@Component
class ReleaseReservationHoldResources(
    private val getPerformanceUseCase: GetPerformanceUseCase,
    private val releasePurchaseQuotaUseCase: ReleasePurchaseQuotaUseCase,
    private val releaseSeatsUseCase: ReleaseSeatsUseCase,
) {
    fun release(reservation: Reservation) {
        // 생성과 동일하게 한도 → 좌석 순서로 갱신한다.
        releasePurchaseQuotaUseCase.releaseQuota(
            reservation.userId,
            requireNotNull(getPerformanceUseCase.findPerformance(reservation.performanceId)).concertId,
            reservation.quantity,
        )
        releaseSeatsUseCase.releaseSeats(
            requireNotNull(reservation.id),
            reservation.items.map { it.seatId },
        )
    }
}
