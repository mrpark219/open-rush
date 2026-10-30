package me.park.openrush.reservation.application.port.output

data class HoldSeatsResult(
    val seatIds: List<Long>,
) {
    val quantity: Int
        get() = seatIds.size
}
