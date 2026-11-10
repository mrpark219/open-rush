package me.park.openrush.reservation.application

import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.stereotype.Service
import java.time.Clock
import java.time.Instant
import java.util.UUID

@Service
@ConditionalOnProperty(prefix = "reservation.queue", name = ["enabled"], havingValue = "true")
class ReservationQueue(
    private val clock: Clock,
    @Value("\${reservation.queue.admissions-per-second:20}") admissionsPerSecond: Int,
    @Value("\${reservation.queue.max-in-flight:16}") private val maxInFlight: Int,
    @Value("\${reservation.queue.capacity:10000}") private val capacity: Int,
    @Value("\${reservation.queue.wait-ttl-ms:120000}") private val waitTtlMillis: Long,
    @Value("\${reservation.queue.permit-ttl-ms:10000}") private val permitTtlMillis: Long,
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
        byUser[userId]?.let { return view(it) }
        if (byId.size >= capacity) throw QueueException(QueueError.QUEUE_FULL)

        val ticket = Ticket(UUID.randomUUID(), userId, ++sequence, now.plusMillis(waitTtlMillis))
        byId[ticket.id] = ticket
        byUser[userId] = ticket
        waiting.addLast(ticket)
        admitNext(now)
        return view(ticket)
    }

    @Synchronized
    fun status(ticketId: UUID): QueueTicketView {
        advance()
        return view(byId[ticketId] ?: throw QueueException(QueueError.QUEUE_TICKET_NOT_FOUND))
    }

    @Synchronized
    fun consume(userId: Long, ticketHeader: String?): AutoCloseable {
        advance()
        val ticket = byUser[userId]
        if (ticket == null || ticketHeader != ticket.id.toString() || ticket.status != QueueTicketStatus.ADMITTED) {
            throw QueueException(QueueError.QUEUE_ADMISSION_REQUIRED)
        }
        admitted.remove(ticket)
        ticket.status = QueueTicketStatus.PROCESSING
        processing++
        return AutoCloseable { finish(ticket) }
    }

    @Synchronized
    private fun finish(ticket: Ticket) {
        if (byId[ticket.id] !== ticket) return
        forget(ticket)
        processing--
        advance()
    }

    private fun advance(): Instant {
        val now = maxOf(lastObservedAt, clock.instant())
        lastObservedAt = now
        while (waiting.firstOrNull()?.let { it.expiresAt <= now } == true) {
            forget(waiting.removeFirst())
        }
        val permits = admitted.iterator()
        while (permits.hasNext()) {
            val ticket = permits.next()
            if (ticket.expiresAt > now) break
            permits.remove()
            forget(ticket)
        }
        admitNext(now)
        return now
    }

    private fun admitNext(now: Instant) {
        if (now < nextAdmissionAt || admitted.size + processing >= maxInFlight) return
        val ticket = waiting.removeFirstOrNull() ?: return
        ticket.status = QueueTicketStatus.ADMITTED
        ticket.expiresAt = now.plusMillis(permitTtlMillis)
        admitted.add(ticket)
        nextAdmissionAt = now.plusNanos(intervalNanos)
    }

    private fun forget(ticket: Ticket) {
        byId.remove(ticket.id)
        byUser.remove(ticket.userId)
    }

    private fun view(ticket: Ticket) = QueueTicketView(
        ticketId = ticket.id,
        status = ticket.status,
        position = if (ticket.status == QueueTicketStatus.WAITING) ticket.sequence - waiting.first().sequence + 1 else null,
        expiresAt = ticket.expiresAt.takeUnless { ticket.status == QueueTicketStatus.PROCESSING },
    )

    private class Ticket(val id: UUID, val userId: Long, val sequence: Long, var expiresAt: Instant) {
        var status = QueueTicketStatus.WAITING
    }
}

data class QueueTicketView(val ticketId: UUID, val status: QueueTicketStatus, val position: Long?, val expiresAt: Instant?)

enum class QueueTicketStatus { WAITING, ADMITTED, PROCESSING }

enum class QueueError(val message: String) {
    QUEUE_FULL("대기열이 가득 찼습니다."),
    QUEUE_TICKET_NOT_FOUND("대기 번호가 없거나 만료되었습니다."),
    QUEUE_ADMISSION_REQUIRED("유효한 입장 허가가 필요합니다."),
}

class QueueException(val error: QueueError) : RuntimeException(error.message)
