package me.park.openrush.queue.adapter.input.web

import me.park.openrush.queue.application.provided.GetQueueTicketUseCase
import me.park.openrush.queue.application.provided.JoinQueueUseCase
import me.park.openrush.queue.domain.QueueTicketView
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.web.bind.annotation.*
import java.util.*

@RestController
@RequestMapping("/reservation-queue")
@ConditionalOnProperty(prefix = "queue", name = ["enabled"], havingValue = "true")
class ReservationQueueController(
    private val registration: JoinQueueUseCase,
    private val tickets: GetQueueTicketUseCase
) {
    @PostMapping
    fun join(@RequestBody request: QueueJoinRequest): QueueTicketView = registration.join(request.userId)

    @GetMapping("/{ticketId}")
    fun status(@PathVariable ticketId: UUID): QueueTicketView = tickets.status(ticketId)
}
