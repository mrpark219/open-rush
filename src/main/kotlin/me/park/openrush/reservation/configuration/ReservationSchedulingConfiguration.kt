package me.park.openrush.reservation.configuration

import org.springframework.context.annotation.Configuration
import org.springframework.scheduling.annotation.EnableScheduling

@Configuration(proxyBeanMethods = false)
@EnableScheduling
class ReservationSchedulingConfiguration
