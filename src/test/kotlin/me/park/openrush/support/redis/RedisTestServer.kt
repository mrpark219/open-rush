package me.park.openrush.support.redis

import org.springframework.data.redis.connection.RedisStandaloneConfiguration
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory
import org.springframework.data.redis.connection.lettuce.LettuceClientConfiguration
import org.springframework.data.redis.core.StringRedisTemplate
import org.testcontainers.containers.GenericContainer
import org.testcontainers.utility.DockerImageName
import java.time.Duration

/** 각 테스트 클라이언트가 독립적인 연결을 사용하면서 같은 Redis 데이터를 공유한다. */
class RedisTestServer : AutoCloseable {
    private val container = RedisContainer().apply {
        start()
    }

    private val connectionFactories = mutableListOf<LettuceConnectionFactory>()

    val host: String
        get() = container.host

    val port: Int
        get() = container.getMappedPort(6379)

    fun createTemplate(): StringRedisTemplate {
        val configuration = RedisStandaloneConfiguration(host, port)
        val clientConfiguration = LettuceClientConfiguration.builder()
            .commandTimeout(Duration.ofSeconds(2))
            .shutdownTimeout(Duration.ZERO)
            .build()
        val connectionFactory = LettuceConnectionFactory(configuration, clientConfiguration)
        connectionFactories.add(connectionFactory)

        connectionFactory.afterPropertiesSet()
        connectionFactory.start()

        return StringRedisTemplate(connectionFactory)
    }

    fun stopRedis() {
        container.stop()
    }

    override fun close() {
        try {
            connectionFactories.forEach { connectionFactory ->
                connectionFactory.destroy()
            }
        } finally {
            container.stop()
        }
    }

    private class RedisContainer : GenericContainer<RedisContainer>(DockerImageName.parse("redis:8.2.11-alpine")) {
        init {
            withExposedPorts(6379)
        }
    }
}
