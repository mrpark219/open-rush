package me.park.openrush.reservation.application

import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals

class ReservationQueueTest {
    private val clock = MutableClock()

    @Test
    fun `중복 등록을 재사용하고 설정한 간격마다 순서대로 허가한다`() {
        val queue = queue(maxInFlight = 3)
        val first = queue.join(1)
        val second = queue.join(2)
        val third = queue.join(3)
        assertEquals(QueueTicketStatus.ADMITTED, first.status)
        assertEquals(second, queue.join(2))
        assertEquals(1L, second.position)
        assertEquals(2L, third.position)

        clock.advance(499)
        assertEquals(QueueTicketStatus.WAITING, queue.status(second.ticketId).status)
        clock.advance(1)
        assertEquals(QueueTicketStatus.ADMITTED, queue.status(second.ticketId).status)
        assertEquals(1L, queue.status(third.ticketId).position)
        clock.advance(500)
        assertEquals(QueueTicketStatus.ADMITTED, queue.status(third.ticketId).status)
    }

    @Test
    fun `사용 중인 자리는 만료되지 않고 반환한 뒤에 다음 사용자를 허가한다`() {
        val queue = queue(maxInFlight = 1)
        val first = queue.join(1)
        val second = queue.join(2)
        assertError(QueueError.QUEUE_ADMISSION_REQUIRED) { queue.consume(2, first.ticketId.toString()) }
        val permit = queue.consume(1, first.ticketId.toString())
        assertEquals(QueueTicketStatus.PROCESSING, queue.join(1).status)
        assertError(QueueError.QUEUE_ADMISSION_REQUIRED) { queue.consume(1, first.ticketId.toString()) }

        clock.advance(1_000)
        assertEquals(QueueTicketStatus.WAITING, queue.status(second.ticketId).status)
        permit.close()
        permit.close()
        assertEquals(QueueTicketStatus.ADMITTED, queue.status(second.ticketId).status)
        assertError(QueueError.QUEUE_ADMISSION_REQUIRED) { queue.consume(1, first.ticketId.toString()) }
    }

    @Test
    fun `사용하지 않은 허가는 기한에 만료되고 다음 사용자에게 자리가 넘어간다`() {
        val queue = queue(maxInFlight = 1)
        val first = queue.join(1)
        val second = queue.join(2)
        clock.advance(1_000)

        assertError(QueueError.QUEUE_ADMISSION_REQUIRED) { queue.consume(1, first.ticketId.toString()) }
        assertError(QueueError.QUEUE_TICKET_NOT_FOUND) { queue.status(first.ticketId) }
        assertEquals(QueueTicketStatus.ADMITTED, queue.status(second.ticketId).status)
        assertNotEquals(first.ticketId, queue.join(1).ticketId)
    }

    @Test
    fun `가득 찬 대기열은 신규 요청을 거절하고 만료된 대기표는 회수한다`() {
        val queue = queue(maxInFlight = 1, capacity = 2)
        val first = queue.join(1)
        val permit = queue.consume(1, first.ticketId.toString())
        val second = queue.join(2)
        assertEquals(second, queue.join(2))
        assertError(QueueError.QUEUE_FULL) { queue.join(3) }

        clock.advance(5_000)
        assertError(QueueError.QUEUE_TICKET_NOT_FOUND) { queue.status(second.ticketId) }
        val third = queue.join(3)
        assertEquals(1L, third.position)
        permit.close()
        assertEquals(QueueTicketStatus.ADMITTED, queue.status(third.ticketId).status)
    }

    private fun queue(maxInFlight: Int, capacity: Int = 10) = ReservationQueue(
        clock, admissionsPerSecond = 2, maxInFlight = maxInFlight, capacity = capacity,
        waitTtlMillis = 5_000, permitTtlMillis = 1_000,
    )

    private fun assertError(error: QueueError, action: () -> Unit) {
        assertEquals(error, assertFailsWith<QueueException> { action() }.error)
    }

    private class MutableClock : Clock() {
        private var now = Instant.parse("2026-11-09T00:00:00Z")
        fun advance(millis: Long) { now = now.plusMillis(millis) }
        override fun instant(): Instant = now
        override fun getZone(): ZoneId = ZoneOffset.UTC
        override fun withZone(zone: ZoneId): Clock = Clock.fixed(now, zone)
    }
}
