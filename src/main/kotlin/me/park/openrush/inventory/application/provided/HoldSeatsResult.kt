package me.park.openrush.inventory.application.provided

data class HoldSeatsResult(
    val seatIds: List<Long>,
) {
    val quantity: Int
        get() = seatIds.size
}
