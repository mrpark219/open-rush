package me.park.openrush.queue.application.provided

/** 만료된 대기표와 처리 점유를 회수하고 입장 가능한 다음 사용자를 허가한다. */
fun interface AdvanceQueueUseCase {
    fun advance()
}
