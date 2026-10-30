package me.park.openrush.reservation.configuration

import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import java.time.Clock

@Configuration(proxyBeanMethods = false)
class ReservationTimeConfiguration {
    @Bean
    fun reservationClock(): Clock = Clock.systemUTC()
}
