package me.park.openrush.queue.adapter.output.redis

import org.springframework.boot.context.properties.ConfigurationProperties

@ConfigurationProperties(prefix = "queue")
data class RedisQueueSettings(
    val admissionsPerSecond: Int = 20,
    val maxInFlight: Int = 16,
    val capacity: Int = 10000,
    val waitTtlMs: Long = 120000,
    val permitTtlMs: Long = 10000,
    val processingTtlMs: Long = 30000,
    val cleanupBatchSize: Int = 100,
    val keyPrefix: String = "queue:{reservation}",
) {
    init {
        require(admissionsPerSecond > 0)
        require(maxInFlight > 0 && capacity >= maxInFlight)
        require(waitTtlMs > 0 && permitTtlMs > 0 && processingTtlMs > 0)
        require(cleanupBatchSize in 1..1000)
        require(Regex(".*\\{[^{}]+}.*").matches(keyPrefix)) {
            "queue.key-prefix must contain a non-empty Redis hash tag"
        }
    }
}
