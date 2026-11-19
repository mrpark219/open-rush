package me.park.openrush.inventory.application.provided

/** 해당 예매가 선점한 좌석을 반환하여 다시 예매할 수 있게 한다. */
fun interface ReleaseSeatsUseCase {
    fun releaseSeats(reservationId: Long, seatIds: List<Long>)
}
