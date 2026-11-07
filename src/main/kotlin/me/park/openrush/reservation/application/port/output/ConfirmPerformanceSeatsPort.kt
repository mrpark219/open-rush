package me.park.openrush.reservation.application.port.output

import java.time.Instant

fun interface ConfirmPerformanceSeatsPort {
    /** 해당 예매가 선점 중인 좌석을 모두 확정한다. 하나라도 확정하지 못하면 예외를 던진다. */
    fun confirmSeats(reservationId: Long, seatIds: List<Long>, confirmedAt: Instant)
}
