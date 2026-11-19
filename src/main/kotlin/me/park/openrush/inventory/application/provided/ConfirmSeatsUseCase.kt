package me.park.openrush.inventory.application.provided

/** 해당 예매가 선점한 좌석을 모두 확정 상태로 전환한다. */
fun interface ConfirmSeatsUseCase {
    fun confirmSeats(reservationId: Long, seatIds: List<Long>, confirmedAt: java.time.Instant)
}
