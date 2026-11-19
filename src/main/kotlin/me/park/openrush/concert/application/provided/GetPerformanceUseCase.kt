package me.park.openrush.concert.application.provided

/** 공연 회차의 예매 가능 시간과 구매 한도 정보를 조회한다. 회차가 없으면 null을 반환한다. */
fun interface GetPerformanceUseCase {
    fun findPerformance(performanceId: Long): PerformanceDetails?
}
