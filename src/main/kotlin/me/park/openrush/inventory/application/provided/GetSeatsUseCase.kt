package me.park.openrush.inventory.application.provided

/** 공연 회차에 속한 요청 좌석의 가격과 상태를 조회한다. */
fun interface GetSeatsUseCase {
    fun findSeats(performanceId: Long, seatIds: List<Long>): List<SeatDetails>
}
