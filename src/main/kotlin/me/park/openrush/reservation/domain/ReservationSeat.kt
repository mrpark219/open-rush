package me.park.openrush.reservation.domain

data class ReservationSeat(val id: Long?, val performanceId: Long, val amount: Long, val available: Boolean) {
    init {
        require(amount >= 0) { "금액은 음수일 수 없습니다." }
    }
}
