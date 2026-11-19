package me.park.openrush.queue.application.provided

import me.park.openrush.queue.domain.QueueTicketView
import java.util.*

/** 대기표의 현재 상태와 대기 순서, 만료 시각을 조회한다. */
fun interface GetQueueTicketUseCase {
    fun status(ticketId: UUID): QueueTicketView
}
