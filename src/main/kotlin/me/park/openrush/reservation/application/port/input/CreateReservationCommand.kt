package me.park.openrush.reservation.application.port.input

data class CreateReservationCommand(
    val userId: Long,
    val performanceId: Long,
    val seatIds: List<Long>,
) {
    init {
        require(userId > 0) { "사용자 ID는 양수여야 합니다." }
        require(performanceId > 0) { "회차 ID는 양수여야 합니다." }
        require(seatIds.isNotEmpty()) { "좌석을 하나 이상 선택해야 합니다." }
        require(seatIds.all { it > 0 }) { "좌석 ID는 양수여야 합니다." }
        require(seatIds.distinct().size == seatIds.size) { "중복 좌석은 선택할 수 없습니다." }
    }
}
