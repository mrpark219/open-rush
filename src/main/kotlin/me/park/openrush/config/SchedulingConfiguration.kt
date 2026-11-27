package me.park.openrush.config

import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.scheduling.annotation.EnableScheduling
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler

@Configuration(proxyBeanMethods = false)
@EnableScheduling
class SchedulingConfiguration {
    @Bean
    fun taskScheduler() = ThreadPoolTaskScheduler().apply {
        setThreadNamePrefix("scheduling-")
    }

    @Bean
    fun reservationRefundTaskScheduler() = ThreadPoolTaskScheduler().apply {
        setThreadNamePrefix("reservation-refund-")
    }
}
