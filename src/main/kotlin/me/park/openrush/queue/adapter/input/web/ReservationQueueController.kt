package me.park.openrush.queue.adapter.input.web

import me.park.openrush.queue.application.provided.GetQueueTicketUseCase
import me.park.openrush.queue.application.provided.JoinQueueUseCase
import me.park.openrush.queue.domain.QueueTicketView
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import java.util.UUID

@RestController
@RequestMapping("/reservation-queue")
@ConditionalOnProperty(prefix = "queue", name = ["enabled"], havingValue = "true")
class ReservationQueueController(
    private val joinQueueUseCase: JoinQueueUseCase,
    private val getQueueTicketUseCase: GetQueueTicketUseCase,
) {
    @PostMapping
    fun join(@RequestBody request: QueueJoinRequest): QueueTicketView = joinQueueUseCase.join(request.userId)

    @GetMapping("/{ticketId}")
    fun status(@PathVariable ticketId: UUID): QueueTicketView = getQueueTicketUseCase.status(ticketId)
}
