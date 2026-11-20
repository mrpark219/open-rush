package me.park.openrush.inventory.application.provided

/** 사용 가능한 요청 좌석을 해당 예매가 선점한다. 모든 좌석을 확보하지 못하면 롤백한다. */
fun interface HoldSeatsUseCase {
    fun hold(holdSeatsCommand: HoldSeatsCommand): HoldSeatsResult
}
