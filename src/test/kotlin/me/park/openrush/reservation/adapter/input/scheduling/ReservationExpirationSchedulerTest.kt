package me.park.openrush.reservation.adapter.input.scheduling

import me.park.openrush.reservation.application.port.input.ExpireReservationHoldsResult
import me.park.openrush.reservation.application.port.input.ExpireReservationHoldsUseCase
import me.park.openrush.reservation.configuration.ReservationSchedulingConfiguration
import org.junit.jupiter.api.Test
import org.mockito.Mockito.mock
import org.mockito.Mockito.timeout
import org.mockito.Mockito.verify
import org.mockito.Mockito.`when`
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.test.context.TestPropertySource
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig

@SpringJUnitConfig(classes = [ReservationSchedulingConfiguration::class, ReservationExpirationScheduler::class,
    ReservationExpirationSchedulerTest.TestBeans::class])
@TestPropertySource(properties = ["reservation.expiration.enabled=true", "reservation.expiration.fixed-delay=20ms",
    "reservation.expiration.initial-delay=0s"])
class ReservationExpirationSchedulerTest {
    @Autowired private lateinit var expiration: ExpireReservationHoldsUseCase

    @Test
    fun `스케줄러가 만료 유스케이스를 주기적으로 실행한다`() {
        verify(expiration, timeout(3000).atLeast(2)).expireHolds()
    }

    @Configuration(proxyBeanMethods = false)
    class TestBeans {
        @Bean
        fun expiration(): ExpireReservationHoldsUseCase = mock(ExpireReservationHoldsUseCase::class.java).also {
            `when`(it.expireHolds()).thenReturn(ExpireReservationHoldsResult(0, 0, 0))
        }
    }
}
