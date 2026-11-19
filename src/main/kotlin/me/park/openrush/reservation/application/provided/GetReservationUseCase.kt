package me.park.openrush.reservation.application.provided


/** 요청한 사용자 소유의 예매 상세 정보를 조회한다. 조회할 수 있는 예매가 없으면 null을 반환한다. */
fun interface GetReservationUseCase {
    fun find(reservationId: Long, userId: Long): ReservationDetails?
}
