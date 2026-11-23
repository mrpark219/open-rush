package me.park.openrush.queue.adapter.output.memory

import me.park.openrush.queue.application.required.QueueStorePort
import me.park.openrush.queue.domain.QueueTicketView
import me.park.openrush.queue.domain.ReservationQueue
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.stereotype.Component
import java.time.Clock
import java.util.UUID

@Component
@ConditionalOnProperty(prefix = "queue", name = ["enabled"], havingValue = "true")
class InMemoryQueueAdapter(
    clock: Clock,
    @Value("\${queue.admissions-per-second:20}") admissionsPerSecond: Int,
    @Value("\${queue.max-in-flight:16}") maxInFlight: Int,
    @Value("\${queue.capacity:10000}") capacity: Int,
    @Value("\${queue.wait-ttl-ms:120000}") waitTtlMillis: Long,
    @Value("\${queue.permit-ttl-ms:10000}") permitTtlMillis: Long,
) : QueueStorePort {
    private val reservationQueue = ReservationQueue(
        clock = clock,
        admissionsPerSecond = admissionsPerSecond,
        maxInFlight = maxInFlight,
        capacity = capacity,
        waitTtlMillis = waitTtlMillis,
        permitTtlMillis = permitTtlMillis,
    )

    override fun join(userId: Long): QueueTicketView = reservationQueue.join(userId)

    override fun status(ticketId: UUID): QueueTicketView = reservationQueue.status(ticketId)

    override fun consume(userId: Long, ticketHeader: String?): AutoCloseable = reservationQueue.consume(userId, ticketHeader)
}
