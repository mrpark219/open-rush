package me.park.openrush.reservation.adapter.input.web

import me.park.openrush.reservation.application.QueueTicketView
import me.park.openrush.reservation.application.ReservationQueue
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
@ConditionalOnProperty(prefix = "reservation.queue", name = ["enabled"], havingValue = "true")
class ReservationQueueController(private val queue: ReservationQueue) {
    @PostMapping
    fun join(@RequestBody request: QueueJoinRequest): QueueTicketView = queue.join(request.userId)

    @GetMapping("/{ticketId}")
    fun status(@PathVariable ticketId: UUID): QueueTicketView = queue.status(ticketId)
}

data class QueueJoinRequest(val userId: Long)
