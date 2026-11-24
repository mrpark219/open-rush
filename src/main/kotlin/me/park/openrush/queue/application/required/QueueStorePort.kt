package me.park.openrush.queue.application.required

import me.park.openrush.queue.domain.QueueTicketView
import java.util.UUID

interface QueueStorePort {
    fun join(userId: Long): QueueTicketView
    fun status(ticketId: UUID): QueueTicketView
    fun consume(userId: Long, ticketHeader: String?): AutoCloseable

    fun advance()
}
