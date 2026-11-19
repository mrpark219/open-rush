package me.park.openrush.concert.application.provided

/** 공연의 구매 한도 정보를 조회한다. 공연이 없으면 null을 반환한다. */
fun interface GetConcertUseCase {
    fun findConcert(concertId: Long): ConcertDetails?
}
