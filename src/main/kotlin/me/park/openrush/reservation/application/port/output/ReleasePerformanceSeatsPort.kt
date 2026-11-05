package me.park.openrush.reservation.application.port.output

fun interface ReleasePerformanceSeatsPort {
    /** 해당 예매가 HELD 상태로 소유한 좌석만 해제한다. 하나라도 해제하지 못하면 전체를 롤백한다. */
    fun releaseSeats(reservationId: Long, seatIds: List<Long>)
}
