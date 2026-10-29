package me.park.openrush.reservation.application.port.output

data class AllocateQuotaCommand(
    val userId: Long,
    val concertId: Long,
    val quantity: Int,
) {
    init {
        require(userId > 0) { "사용자 ID는 양수여야 합니다." }
        require(concertId > 0) { "공연 ID는 양수여야 합니다." }
        require(quantity > 0) { "확보할 수량은 양수여야 합니다." }
    }
}
