package me.park.openrush.reservation.application

import me.park.openrush.reservation.application.port.output.ReleasePerformanceSeatsPort
import me.park.openrush.reservation.application.port.output.ReleasePurchaseQuotaPort
import me.park.openrush.reservation.domain.Reservation
import org.springframework.stereotype.Component

@Component
class ReleaseReservationHoldResources(
    private val purchaseQuotas: ReleasePurchaseQuotaPort,
    private val performanceSeats: ReleasePerformanceSeatsPort,
) {
    fun release(reservation: Reservation) {
        // 생성과 동일하게 한도 → 좌석 순서로 갱신한다.
        purchaseQuotas.releaseQuota(
            reservation.userId,
            requireNotNull(reservation.performance.concert.id),
            reservation.quantity,
        )
        performanceSeats.releaseSeats(
            requireNotNull(reservation.id),
            reservation.items.map { requireNotNull(it.seat.id) },
        )
    }
}
