package me.park.openrush

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.boot.data.redis.autoconfigure.DataRedisConnectionDetails
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.testcontainers.containers.GenericContainer
import org.testcontainers.utility.DockerImageName

@TestConfiguration(proxyBeanMethods = false)
@ConditionalOnProperty(prefix = "queue", name = ["enabled"], havingValue = "true")
class RedisTestcontainersConfiguration {
    @Bean
    fun redisConnectionDetails(): DataRedisConnectionDetails = object : DataRedisConnectionDetails {
        override fun getStandalone(): DataRedisConnectionDetails.Standalone {
            return DataRedisConnectionDetails.Standalone.of(
                WorkerRedis.container.host,
                WorkerRedis.container.getMappedPort(6379),
            )
        }
    }
}

/** Redis가 필요한 Spring 테스트만 프로세스별 컨테이너를 시작하고 공유한다. */
private object WorkerRedis {
    val container: GenericContainer<*> = GenericContainer(DockerImageName.parse("redis:8.2.11-alpine"))
        .withExposedPorts(6379)
        .apply {
            start()
        }
}
