package me.park.openrush.config

import me.park.openrush.queue.adapter.output.redis.RedisQueueSettings
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Configuration

@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(prefix = "queue", name = ["enabled"], havingValue = "true")
@EnableConfigurationProperties(RedisQueueSettings::class)
class RedisQueueConfiguration
