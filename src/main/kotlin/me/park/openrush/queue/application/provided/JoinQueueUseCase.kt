package me.park.openrush.queue.application.provided

import me.park.openrush.queue.domain.QueueTicketView

/** 사용자를 예매 대기열에 등록하고 대기표를 반환한다. */
fun interface JoinQueueUseCase {
    fun join(userId: Long): QueueTicketView
}
