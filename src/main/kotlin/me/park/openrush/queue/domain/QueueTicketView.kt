package me.park.openrush.queue.domain

import java.time.Instant
import java.util.*

data class QueueTicketView(
    val ticketId: UUID,
    val status: QueueTicketStatus,
    val position: Long?,
    val expiresAt: Instant?
)
