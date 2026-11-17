package me.park.openrush.config

import me.park.openrush.OpenRushApplication
import org.springframework.cloud.openfeign.EnableFeignClients
import org.springframework.cloud.openfeign.clientconfig.HttpClient5FeignConfiguration.HttpClientBuilderCustomizer
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

@Configuration(proxyBeanMethods = false)
@EnableFeignClients(basePackageClasses = [OpenRushApplication::class])
class FeignConfiguration {
    @Bean
    fun disableFeignTransportRetries(): HttpClientBuilderCustomizer =
        HttpClientBuilderCustomizer { it.disableAutomaticRetries() }
}
