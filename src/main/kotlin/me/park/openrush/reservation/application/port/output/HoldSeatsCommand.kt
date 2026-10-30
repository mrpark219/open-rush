package me.park.openrush.reservation.application.port.output

import me.park.openrush.reservation.domain.Reservation

data class HoldSeatsCommand(
    val reservationId: Long,
    val performanceId: Long,
    val seatIds: List<Long>,
) {
    init {
        require(reservationId > 0) { "저장된 예매 ID가 필요합니다." }
        require(performanceId > 0) { "회차 ID는 양수여야 합니다." }
        require(seatIds.isNotEmpty()) { "좌석을 하나 이상 선택해야 합니다." }
        require(seatIds.all { it > 0 }) { "좌석 ID는 양수여야 합니다." }
        require(seatIds.distinct().size == seatIds.size) { "중복 좌석은 선택할 수 없습니다." }
    }

    companion object {
        fun from(reservation: Reservation): HoldSeatsCommand = HoldSeatsCommand(
            reservationId = requireNotNull(reservation.id),
            performanceId = requireNotNull(reservation.performance.id),
            seatIds = reservation.items.map { requireNotNull(it.seat.id) },
        )
    }
}
