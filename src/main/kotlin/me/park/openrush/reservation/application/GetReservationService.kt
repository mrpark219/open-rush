package me.park.openrush.reservation.application

import me.park.openrush.reservation.application.port.input.GetReservationUseCase
import me.park.openrush.reservation.application.port.input.ReservationDetails
import me.park.openrush.reservation.application.port.output.LoadReservationPort
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

@Service
class GetReservationService(private val loadReservationPort: LoadReservationPort) : GetReservationUseCase {
    @Transactional(readOnly = true)
    override fun find(reservationId: Long, userId: Long): ReservationDetails? {
        require(reservationId > 0 && userId > 0) { "예매 ID와 사용자 ID는 양수여야 합니다." }
        val reservation = loadReservationPort.findReservation(reservationId)
            ?.takeIf { it.userId == userId } ?: return null

        return ReservationDetails(
            reservationId = reservationId,
            performanceId = requireNotNull(reservation.performance.id),
            status = reservation.status,
            heldAt = reservation.heldAt,
            expiresAt = reservation.expiresAt,
            confirmedAt = reservation.confirmedAt,
            quantity = reservation.quantity,
            totalAmount = reservation.totalAmount.amount,
            seatIds = reservation.items.map { requireNotNull(it.seat.id) }.sorted(),
        )
    }
}
