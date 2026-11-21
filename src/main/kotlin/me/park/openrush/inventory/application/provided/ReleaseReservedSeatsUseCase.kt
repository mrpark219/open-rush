package me.park.openrush.inventory.application.provided

/** 취소한 예약이 확정한 좌석을 반환한다. 다른 예약이 점유한 좌석은 변경하지 않는다. */
fun interface ReleaseReservedSeatsUseCase {
    fun releaseReservedSeats(reservationId: Long, seatIds: List<Long>)
}
