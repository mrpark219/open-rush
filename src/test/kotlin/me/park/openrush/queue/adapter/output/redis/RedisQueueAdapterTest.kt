package me.park.openrush.queue.adapter.output.redis

import me.park.openrush.queue.domain.QueueError
import me.park.openrush.queue.domain.QueueTicketStatus
import me.park.openrush.queue.domain.exception.QueueException
import me.park.openrush.queue.application.exception.QueueUnavailableException
import me.park.openrush.support.redis.RedisTestServer
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.springframework.core.io.ClassPathResource
import org.springframework.data.redis.core.RedisCallback
import org.springframework.data.redis.core.StringRedisTemplate
import java.security.MessageDigest
import java.time.Duration
import java.time.Instant
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class RedisQueueAdapterTest {
    private lateinit var redisTestServer: RedisTestServer
    private lateinit var firstRedisTemplate: StringRedisTemplate
    private lateinit var secondRedisTemplate: StringRedisTemplate

    @BeforeAll
    fun startRedis() {
        redisTestServer = RedisTestServer()
        firstRedisTemplate = redisTestServer.createTemplate()
        secondRedisTemplate = redisTestServer.createTemplate()
    }

    @AfterAll
    fun stopRedis() {
        redisTestServer.close()
    }

    @Test
    fun `서로 다른 서버에서 같은 사용자를 동시에 등록해도 하나의 대기표를 반환한다`() {
        val (firstQueue, secondQueue) = queues()

        val tickets = concurrently(16) { index ->
            val queue = if (index % 2 == 0) firstQueue else secondQueue
            queue.join(1001)
        }

        assertEquals(1, tickets.map { ticket -> ticket.ticketId }.toSet().size)
        assertTrue(tickets.all { ticket -> ticket.status == QueueTicketStatus.ADMITTED })
    }

    @Test
    fun `여러 서버가 같은 입장 간격과 대기 순서를 공유한다`() {
        val (firstQueue, secondQueue) = queues(admissionsPerSecond = 2, maxInFlight = 3)

        val firstTicket = firstQueue.join(1)
        val secondTicket = secondQueue.join(2)
        val thirdTicket = firstQueue.join(3)

        assertEquals(QueueTicketStatus.ADMITTED, firstTicket.status)
        assertEquals(QueueTicketStatus.WAITING, secondTicket.status)
        assertEquals(1L, secondTicket.position)
        assertEquals(QueueTicketStatus.WAITING, thirdTicket.status)
        assertEquals(2L, thirdTicket.position)

        awaitCondition {
            secondQueue.status(secondTicket.ticketId).status == QueueTicketStatus.ADMITTED
        }

        val waitingTicket = firstQueue.status(thirdTicket.ticketId)
        assertEquals(QueueTicketStatus.WAITING, waitingTicket.status)
        assertEquals(1L, waitingTicket.position)

        awaitCondition {
            firstQueue.status(thirdTicket.ticketId).status == QueueTicketStatus.ADMITTED
        }
    }

    @Test
    fun `같은 대기표를 여러 서버에서 동시에 소비하면 한 요청만 처리 권한을 얻는다`() {
        val (firstQueue, secondQueue) = queues()
        val ticket = firstQueue.join(1001)

        val consumeResults = concurrently(16) { index ->
            val queue = if (index % 2 == 0) firstQueue else secondQueue
            runCatching { queue.consume(1001, ticket.ticketId.toString()) }
        }

        val acquiredPermits = consumeResults.mapNotNull { result -> result.getOrNull() }
        try {
            assertEquals(1, acquiredPermits.size)
            assertEquals(QueueTicketStatus.PROCESSING, secondQueue.status(ticket.ticketId).status)

            val rejections = consumeResults.mapNotNull { result -> result.exceptionOrNull() }
            assertEquals(15, rejections.size)
            assertTrue(rejections.all { exception ->
                exception is QueueException && exception.error == QueueError.QUEUE_ADMISSION_REQUIRED
            })
        } finally {
            acquiredPermits.forEach { permit -> permit.close() }
        }
    }

    @Test
    fun `사용자가 다르면 입장 권한을 사용할 수 없고 처리 종료 후 다음 사용자가 입장한다`() {
        val (firstQueue, secondQueue) = queues()
        val firstTicket = firstQueue.join(1)
        val secondTicket = secondQueue.join(2)

        assertError(QueueError.QUEUE_ADMISSION_REQUIRED) {
            secondQueue.consume(2, firstTicket.ticketId.toString())
        }

        val permit = secondQueue.consume(1, firstTicket.ticketId.toString())
        try {
            assertEquals(QueueTicketStatus.WAITING, firstQueue.status(secondTicket.ticketId).status)
            assertEquals(QueueTicketStatus.PROCESSING, firstQueue.join(1).status)
        } finally {
            permit.close()
        }

        permit.close()

        awaitCondition {
            secondQueue.status(secondTicket.ticketId).status == QueueTicketStatus.ADMITTED
        }
        assertError(QueueError.QUEUE_ADMISSION_REQUIRED) {
            firstQueue.consume(1, firstTicket.ticketId.toString())
        }
    }

    @Test
    fun `사용하지 않은 입장 허가가 만료되면 다음 사용자에게 자리를 넘긴다`() {
        val (firstQueue, secondQueue) = queues(permitTtlMs = 200)
        val firstTicket = firstQueue.join(1)
        val secondTicket = secondQueue.join(2)

        awaitCondition {
            secondQueue.status(secondTicket.ticketId).status == QueueTicketStatus.ADMITTED
        }

        assertError(QueueError.QUEUE_TICKET_NOT_FOUND) {
            firstQueue.status(firstTicket.ticketId)
        }
        assertError(QueueError.QUEUE_ADMISSION_REQUIRED) {
            firstQueue.consume(1, firstTicket.ticketId.toString())
        }
        assertNotEquals(firstTicket.ticketId, firstQueue.join(1).ticketId)
    }

    @Test
    fun `대기열이 가득 차면 거절하고 대기 만료 후에는 새 사용자를 받는다`() {
        val (firstQueue, secondQueue) = queues(capacity = 2, waitTtlMs = 200)
        val firstTicket = firstQueue.join(1)
        val permit = firstQueue.consume(1, firstTicket.ticketId.toString())

        try {
            val secondTicket = secondQueue.join(2)
            assertEquals(secondTicket, firstQueue.join(2))

            assertError(QueueError.QUEUE_FULL) {
                secondQueue.join(3)
            }

            awaitCondition {
                secondQueue.advance()
                runCatching { firstQueue.status(secondTicket.ticketId) }
                    .exceptionOrNull()
                    .let { exception ->
                        exception is QueueException && exception.error == QueueError.QUEUE_TICKET_NOT_FOUND
                    }
            }

            val thirdTicket = firstQueue.join(3)
            assertEquals(QueueTicketStatus.WAITING, thirdTicket.status)
            assertEquals(1L, thirdTicket.position)
        } finally {
            permit.close()
        }
    }

    @Test
    fun `처리 서버가 반환하지 않아도 만료 후 회수하고 늦은 반환은 새 점유를 지우지 않는다`() {
        val (firstQueue, secondQueue) = queues(processingTtlMs = 200)
        val oldTicket = firstQueue.join(1001)
        val abandonedPermit = firstQueue.consume(1001, oldTicket.ticketId.toString())

        awaitCondition {
            secondQueue.advance()
            runCatching { secondQueue.status(oldTicket.ticketId) }
                .exceptionOrNull()
                .let { exception ->
                    exception is QueueException && exception.error == QueueError.QUEUE_TICKET_NOT_FOUND
                }
        }

        val newTicket = secondQueue.join(1001)
        assertNotEquals(oldTicket.ticketId, newTicket.ticketId)
        assertEquals(QueueTicketStatus.ADMITTED, newTicket.status)

        val newPermit = secondQueue.consume(1001, newTicket.ticketId.toString())
        try {
            abandonedPermit.close()
            abandonedPermit.close()

            assertEquals(QueueTicketStatus.PROCESSING, firstQueue.status(newTicket.ticketId).status)
            assertEquals(newTicket.ticketId, firstQueue.join(1001).ticketId)
            assertEquals(QueueTicketStatus.WAITING, firstQueue.join(1002).status)
        } finally {
            newPermit.close()
        }
    }

    @Test
    fun `Lua 사전 로드 후 스크립트 캐시가 사라져도 재등록하고 계속 처리한다`() {
        val (firstQueue, secondQueue) = queues()
        firstQueue.preloadScripts()
        secondQueue.preloadScripts()

        val script = ClassPathResource("redis/reservation-queue.lua").inputStream.use { stream -> stream.readBytes() }
        val scriptSha = MessageDigest.getInstance("SHA-1").digest(script)
            .joinToString("") { byte -> "%02x".format(byte) }

        assertEquals(listOf(true), scriptExists(scriptSha))

        val ticket = firstQueue.join(1001)

        firstRedisTemplate.execute(RedisCallback<Unit> { connection ->
            connection.scriptingCommands().scriptFlush()
        })

        assertEquals(listOf(false), scriptExists(scriptSha))
        assertEquals(ticket.ticketId, secondQueue.status(ticket.ticketId).ticketId)
        assertEquals(listOf(true), scriptExists(scriptSha))
        assertEquals(ticket.ticketId, firstQueue.join(1001).ticketId)

        secondQueue.consume(1001, ticket.ticketId.toString()).use {
            assertEquals(QueueTicketStatus.PROCESSING, firstQueue.status(ticket.ticketId).status)
        }
    }

    @Test
    fun `만료 항목이 정리 배치보다 많아도 반복 실행하면 모든 점유를 회수한다`() {
        val keyPrefix = "queue:{${UUID.randomUUID()}}"
        val settings = RedisQueueSettings(
            admissionsPerSecond = 1000,
            maxInFlight = 1,
            capacity = 4,
            waitTtlMs = 200,
            permitTtlMs = 5000,
            processingTtlMs = 5000,
            cleanupBatchSize = 1,
            keyPrefix = keyPrefix,
        )
        val firstQueue = RedisQueueAdapter(firstRedisTemplate, settings)
        val secondQueue = RedisQueueAdapter(secondRedisTemplate, settings)
        val firstTicket = firstQueue.join(1)
        val permit = firstQueue.consume(1, firstTicket.ticketId.toString())

        try {
            secondQueue.join(2)
            secondQueue.join(3)
            val lastTicket = secondQueue.join(4)

            awaitCondition {
                Instant.now().isAfter(lastTicket.expiresAt!!.plusMillis(50))
            }

            firstQueue.advance()

            assertEquals(3L, firstRedisTemplate.opsForHash<String, String>().size("$keyPrefix:tickets"))

            repeat(3) {
                secondQueue.advance()
            }

            assertEquals(1L, firstRedisTemplate.opsForHash<String, String>().size("$keyPrefix:tickets"))
            assertEquals(QueueTicketStatus.WAITING, secondQueue.join(5).status)
        } finally {
            permit.close()
        }
    }

    @Test
    fun `Redis 중단 시 새 요청은 실패하고 처리 권한 반환은 기존 처리 결과를 덮어쓰지 않는다`() {
        RedisTestServer().use { isolatedRedisServer ->
            val queue = RedisQueueAdapter(
                isolatedRedisServer.createTemplate(),
                RedisQueueSettings(),
            )
            val ticket = queue.join(1001)
            val permit = queue.consume(1001, ticket.ticketId.toString())

            isolatedRedisServer.stopRedis()

            assertFailsWith<QueueUnavailableException> {
                queue.join(1002)
            }

            // 반환 실패는 처리 권한의 만료로 회수하며, 이미 끝난 예약 결과는 그대로 유지한다.
            permit.close()
        }
    }

    private fun queues(
        admissionsPerSecond: Int = 1000,
        maxInFlight: Int = 1,
        capacity: Int = 10,
        waitTtlMs: Long = 5000,
        permitTtlMs: Long = 5000,
        processingTtlMs: Long = 5000,
    ): Pair<RedisQueueAdapter, RedisQueueAdapter> {
        val settings = RedisQueueSettings(
            admissionsPerSecond = admissionsPerSecond,
            maxInFlight = maxInFlight,
            capacity = capacity,
            waitTtlMs = waitTtlMs,
            permitTtlMs = permitTtlMs,
            processingTtlMs = processingTtlMs,
            keyPrefix = "queue:{${UUID.randomUUID()}}",
        )

        return RedisQueueAdapter(firstRedisTemplate, settings) to RedisQueueAdapter(secondRedisTemplate, settings)
    }

    private fun <T> concurrently(count: Int, action: (Int) -> T): List<T> {
        val ready = CountDownLatch(count)
        val start = CountDownLatch(1)
        val executor = Executors.newFixedThreadPool(count)

        try {
            val futures = (0 until count).map { index ->
                executor.submit<T> {
                    ready.countDown()
                    check(start.await(5, TimeUnit.SECONDS)) { "동시 요청 시작 신호를 받지 못했습니다." }
                    action(index)
                }
            }

            check(ready.await(5, TimeUnit.SECONDS)) { "동시 요청 작업자가 준비되지 않았습니다." }
            start.countDown()

            return futures.map { future -> future.get(10, TimeUnit.SECONDS) }
        } finally {
            start.countDown()
            executor.shutdownNow()
            check(executor.awaitTermination(5, TimeUnit.SECONDS)) { "동시 요청 작업자가 종료되지 않았습니다." }
        }
    }

    private fun awaitCondition(condition: () -> Boolean) {
        val deadline = System.nanoTime() + Duration.ofSeconds(3).toNanos()
        while (System.nanoTime() < deadline) {
            if (condition()) {
                return
            }

            Thread.sleep(10)
        }

        assertTrue(condition(), "제한 시간 안에 대기열 상태가 변경되지 않았습니다.")
    }

    private fun assertError(error: QueueError, action: () -> Unit) {
        assertEquals(error, assertFailsWith<QueueException> { action() }.error)
    }

    private fun scriptExists(scriptSha: String): List<Boolean>? {
        return firstRedisTemplate.execute(RedisCallback { connection ->
            connection.scriptingCommands().scriptExists(scriptSha)
        })
    }
}
