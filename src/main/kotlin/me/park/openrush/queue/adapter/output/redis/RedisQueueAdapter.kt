package me.park.openrush.queue.adapter.output.redis

import jakarta.annotation.PostConstruct
import me.park.openrush.queue.application.exception.QueueUnavailableException
import me.park.openrush.queue.application.required.QueueStorePort
import me.park.openrush.queue.domain.QueueError
import me.park.openrush.queue.domain.QueueTicketStatus
import me.park.openrush.queue.domain.QueueTicketView
import me.park.openrush.queue.domain.exception.QueueException
import org.slf4j.LoggerFactory
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.core.io.ClassPathResource
import org.springframework.dao.DataAccessException
import org.springframework.data.redis.connection.ReturnType
import org.springframework.data.redis.core.RedisCallback
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.stereotype.Component
import java.nio.charset.StandardCharsets.UTF_8
import java.security.MessageDigest
import java.time.Instant
import java.util.UUID

@Component
@ConditionalOnProperty(prefix = "queue", name = ["enabled"], havingValue = "true")
class RedisQueueAdapter(
    private val redisTemplate: StringRedisTemplate,
    private val settings: RedisQueueSettings,
) : QueueStorePort {
    private val logger = LoggerFactory.getLogger(javaClass)
    private val script = ClassPathResource("redis/reservation-queue.lua").inputStream.use { it.readBytes() }
    private val scriptSha = MessageDigest.getInstance("SHA-1").digest(script)
        .joinToString("") { "%02x".format(it) }

    private val keys = listOf(
        "sequence",
        "waiting",
        "waiting-expiry",
        "admitted",
        "processing",
        "tickets",
        "users",
        "next-admission-at",
    ).map { "${settings.keyPrefix}:$it" }

    @PostConstruct
    fun preloadScripts() {
        try {
            redisTemplate.execute(RedisCallback { connection ->
                connection.scriptingCommands().scriptLoad(script)
            })
        } catch (exception: DataAccessException) {
            throw QueueUnavailableException(exception)
        }
    }

    override fun join(userId: Long): QueueTicketView {
        require(userId > 0)

        val result = execute("join", userId.toString(), UUID.randomUUID().toString())

        return ticketView(result)
    }

    override fun status(ticketId: UUID): QueueTicketView {
        val result = execute("status", ticketId = ticketId.toString())

        return ticketView(result)
    }

    override fun consume(userId: Long, ticketHeader: String?): AutoCloseable {
        if (ticketHeader == null) {
            throw QueueException(QueueError.QUEUE_ADMISSION_REQUIRED)
        }

        val processingToken = UUID.randomUUID().toString()
        execute("consume", userId.toString(), ticketHeader, processingToken)

        return AutoCloseable {
            finish(ticketHeader, processingToken)
        }
    }

    override fun advance() {
        execute("advance")
    }

    private fun finish(ticketId: String, processingToken: String) {
        try {
            execute("finish", ticketId = ticketId, processingToken = processingToken)
        } catch (exception: QueueUnavailableException) {
            // 예약 커밋 결과를 덮어쓰지 않는다. 반환하지 못한 점유는 처리 권한 만료로 회수한다.
            logger.warn("Failed to release queue processing permit for ticket {}", ticketId, exception)
        }
    }

    private fun execute(
        operation: String,
        userId: String = "",
        ticketId: String = "",
        processingToken: String = "",
    ): List<String> {
        val arguments = listOf(
            operation,
            userId,
            ticketId,
            processingToken,
            settings.admissionsPerSecond.toString(),
            settings.maxInFlight.toString(),
            settings.capacity.toString(),
            settings.waitTtlMs.toString(),
            settings.permitTtlMs.toString(),
            settings.processingTtlMs.toString(),
            settings.cleanupBatchSize.toString(),
        )
        val keysAndArguments = (keys + arguments).map { it.toByteArray(UTF_8) }.toTypedArray()

        val result = try {
            executeSha(keysAndArguments)
        } catch (exception: DataAccessException) {
            if (!isMissingScript(exception)) {
                throw QueueUnavailableException(exception)
            }

            preloadScripts()

            try {
                executeSha(keysAndArguments)
            } catch (retryException: DataAccessException) {
                throw QueueUnavailableException(retryException)
            }
        }

        if (result.first() != "OK") {
            throw QueueException(QueueError.valueOf(result.first()))
        }

        return result
    }

    private fun executeSha(keysAndArguments: Array<ByteArray>): List<String> {
        val result = redisTemplate.execute(RedisCallback<List<*>> { connection ->
            connection.scriptingCommands().evalSha(scriptSha, ReturnType.MULTI, keys.size, *keysAndArguments)
        }) ?: error("Redis queue script returned no result")

        return result.map { value ->
            when (value) {
                is ByteArray -> String(value, UTF_8)
                else -> value.toString()
            }
        }
    }

    private fun isMissingScript(exception: Throwable): Boolean {
        return generateSequence(exception) { it.cause }.any { cause ->
            cause.message?.contains("NOSCRIPT") == true
        }
    }

    private fun ticketView(result: List<String>): QueueTicketView {
        val position = if (result[3].isNotEmpty()) {
            result[3].toLong()
        } else {
            null
        }

        val expiresAt = if (result[4].isNotEmpty()) {
            Instant.ofEpochMilli(result[4].toLong())
        } else {
            null
        }

        return QueueTicketView(
            ticketId = UUID.fromString(result[1]),
            status = QueueTicketStatus.valueOf(result[2]),
            position = position,
            expiresAt = expiresAt,
        )
    }
}
