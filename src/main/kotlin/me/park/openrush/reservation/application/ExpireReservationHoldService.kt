package me.park.openrush.reservation.application

import me.park.openrush.reservation.application.port.input.ExpireReservationHoldUseCase
import me.park.openrush.reservation.application.port.output.ConfigureExpirationTransactionPort
import me.park.openrush.reservation.application.port.output.ExpireHeldReservationPort
import me.park.openrush.reservation.application.port.output.LoadReservationPort
import me.park.openrush.reservation.application.port.output.ReleasePerformanceSeatsPort
import me.park.openrush.reservation.application.port.output.ReleasePurchaseQuotaPort
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import java.time.Clock

@Service
class ExpireReservationHoldService(
    private val expireHeldReservationPort: ExpireHeldReservationPort,
    private val loadReservationPort: LoadReservationPort,
    private val releasePurchaseQuotaPort: ReleasePurchaseQuotaPort,
    private val releasePerformanceSeatsPort: ReleasePerformanceSeatsPort,
    private val clock: Clock,
    private val configureExpirationTransactionPort: ConfigureExpirationTransactionPort,
) : ExpireReservationHoldUseCase {
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    override fun expireHold(reservationId: Long): Boolean {
        require(reservationId > 0) { "예매 ID는 양수여야 합니다." }
        configureExpirationTransactionPort.configure()
        if (!expireHeldReservationPort.expireHeld(reservationId, clock.instant())) return false
        val reservation = checkNotNull(loadReservationPort.findReservation(reservationId)) {
            "만료 전환한 예매를 찾을 수 없습니다."
        }
        // 취소와 같은 순서로 갱신한다. 반환 실패 시 EXPIRED 전환도 함께 롤백한다.
        releasePurchaseQuotaPort.releaseQuota(
            reservation.userId,
            requireNotNull(reservation.performance.concert.id),
            reservation.quantity,
        )
        releasePerformanceSeatsPort.releaseSeats(
            reservationId,
            reservation.items.map { requireNotNull(it.seat.id) },
        )
        return true
    }
}
