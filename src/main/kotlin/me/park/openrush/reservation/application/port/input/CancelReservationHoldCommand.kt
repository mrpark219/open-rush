package me.park.openrush.reservation.application.port.input

data class CancelReservationHoldCommand(val userId: Long, val reservationId: Long) {
    init {
        require(userId > 0) { "사용자 ID는 양수여야 합니다." }
        require(reservationId > 0) { "예매 ID는 양수여야 합니다." }
    }
}
