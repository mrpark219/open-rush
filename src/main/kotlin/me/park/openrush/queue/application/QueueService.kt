package me.park.openrush.queue.application

import me.park.openrush.queue.application.provided.GetQueueTicketUseCase
import me.park.openrush.queue.application.provided.JoinQueueUseCase
import me.park.openrush.queue.application.provided.ReservationAdmissionUseCase
import me.park.openrush.queue.application.required.QueueStorePort
import me.park.openrush.queue.domain.QueueTicketView
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.stereotype.Service
import java.util.*

@Service
@ConditionalOnProperty(prefix = "queue", name = ["enabled"], havingValue = "true")
class QueueService(private val store: QueueStorePort) : JoinQueueUseCase, GetQueueTicketUseCase,
    ReservationAdmissionUseCase {
    override fun join(userId: Long): QueueTicketView = store.join(userId)
    override fun status(ticketId: UUID): QueueTicketView = store.status(ticketId)
    override fun consume(userId: Long, ticketHeader: String?): AutoCloseable = store.consume(userId, ticketHeader)
}
