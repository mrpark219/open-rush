package me.park.openrush.queue.domain

import me.park.openrush.queue.domain.exception.QueueException
import java.time.Clock
import java.time.Instant
import java.util.UUID

class ReservationQueue(
    private val clock: Clock,
    admissionsPerSecond: Int,
    private val maxInFlight: Int,
    private val capacity: Int,
    private val waitTtlMillis: Long,
    private val permitTtlMillis: Long,
) {
    init {
        require(admissionsPerSecond > 0 && maxInFlight > 0 && capacity >= maxInFlight)
        require(waitTtlMillis > 0 && permitTtlMillis > 0)
    }

    private val intervalNanos = (1_000_000_000L + admissionsPerSecond - 1) / admissionsPerSecond
    private val byId = mutableMapOf<UUID, Ticket>()
    private val byUser = mutableMapOf<Long, Ticket>()
    private val waiting = ArrayDeque<Ticket>()
    private val admitted = linkedSetOf<Ticket>()
    private var processing = 0
    private var sequence = 0L
    private var nextAdmissionAt = Instant.MIN
    private var lastObservedAt = Instant.MIN

    @Synchronized
    fun join(userId: Long): QueueTicketView {
        require(userId > 0)

        val now = advance()
        val existingTicket = byUser[userId]
        if (existingTicket != null) {
            return view(existingTicket)
        }

        if (byId.size >= capacity) {
            throw QueueException(QueueError.QUEUE_FULL)
        }

        val ticket = registerWaitingTicket(userId, now)
        admitNext(now)

        return view(ticket)
    }

    @Synchronized
    fun status(ticketId: UUID): QueueTicketView {
        advance()

        val ticket = byId[ticketId]
        if (ticket == null) {
            throw QueueException(QueueError.QUEUE_TICKET_NOT_FOUND)
        }

        return view(ticket)
    }

    @Synchronized
    fun consume(userId: Long, ticketHeader: String?): AutoCloseable {
        advance()

        val ticket = byUser[userId]
        if (ticket == null ||
            ticketHeader != ticket.id.toString() ||
            ticket.status != QueueTicketStatus.ADMITTED
        ) {
            throw QueueException(QueueError.QUEUE_ADMISSION_REQUIRED)
        }

        admitted.remove(ticket)
        ticket.status = QueueTicketStatus.PROCESSING
        processing++

        return AutoCloseable { finish(ticket) }
    }

    @Synchronized
    private fun finish(ticket: Ticket) {
        if (byId[ticket.id] !== ticket) {
            return
        }

        forget(ticket)
        processing--

        advance()
    }

    private fun registerWaitingTicket(userId: Long, now: Instant): Ticket {
        val ticket = Ticket(
            id = UUID.randomUUID(),
            userId = userId,
            sequence = ++sequence,
            expiresAt = now.plusMillis(waitTtlMillis),
        )

        byId[ticket.id] = ticket
        byUser[userId] = ticket
        waiting.addLast(ticket)

        return ticket
    }

    private fun advance(): Instant {
        val now = maxOf(lastObservedAt, clock.instant())
        lastObservedAt = now

        removeExpiredWaitingTickets(now)
        removeExpiredAdmissions(now)
        admitNext(now)

        return now
    }

    private fun removeExpiredWaitingTickets(now: Instant) {
        while (waiting.isNotEmpty()) {
            if (waiting.first().expiresAt > now) {
                break
            }

            forget(waiting.removeFirst())
        }
    }

    private fun removeExpiredAdmissions(now: Instant) {
        val permits = admitted.iterator()
        while (permits.hasNext()) {
            val ticket = permits.next()
            if (ticket.expiresAt > now) {
                break
            }

            permits.remove()
            forget(ticket)
        }
    }

    private fun admitNext(now: Instant) {
        if (now < nextAdmissionAt) {
            return
        }

        if (admitted.size + processing >= maxInFlight) {
            return
        }

        val ticket = waiting.removeFirstOrNull()
        if (ticket == null) {
            return
        }

        ticket.status = QueueTicketStatus.ADMITTED
        ticket.expiresAt = now.plusMillis(permitTtlMillis)
        admitted.add(ticket)

        nextAdmissionAt = now.plusNanos(intervalNanos)
    }

    private fun forget(ticket: Ticket) {
        byId.remove(ticket.id)
        byUser.remove(ticket.userId)
    }

    private fun view(ticket: Ticket): QueueTicketView {
        val position = if (ticket.status == QueueTicketStatus.WAITING) {
            ticket.sequence - waiting.first().sequence + 1
        } else {
            null
        }

        val expiresAt = if (ticket.status == QueueTicketStatus.PROCESSING) {
            null
        } else {
            ticket.expiresAt
        }

        return QueueTicketView(
            ticketId = ticket.id,
            status = ticket.status,
            position = position,
            expiresAt = expiresAt,
        )
    }

    private class Ticket(
        val id: UUID,
        val userId: Long,
        val sequence: Long,
        var expiresAt: Instant,
    ) {
        var status = QueueTicketStatus.WAITING
    }
}
