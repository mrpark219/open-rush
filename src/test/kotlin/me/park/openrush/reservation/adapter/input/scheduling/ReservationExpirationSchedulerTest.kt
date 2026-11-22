package me.park.openrush.reservation.adapter.input.scheduling

import me.park.openrush.config.SchedulingConfiguration
import me.park.openrush.reservation.application.provided.ExpireReservationHoldsResult
import me.park.openrush.reservation.application.provided.ExpireReservationHoldsUseCase
import org.junit.jupiter.api.Test
import org.mockito.Mockito.*
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.test.context.TestPropertySource
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig

@SpringJUnitConfig(
    classes = [SchedulingConfiguration::class, ReservationExpirationScheduler::class,
        ReservationExpirationSchedulerTest.TestBeans::class]
)
@TestPropertySource(
    properties = ["reservation.expiration.enabled=true", "reservation.expiration.fixed-delay=20ms",
        "reservation.expiration.initial-delay=0s"]
)
class ReservationExpirationSchedulerTest {
    @Autowired
    private lateinit var expireReservationHoldsUseCase: ExpireReservationHoldsUseCase

    @Test
    fun `스케줄러가 만료 유스케이스를 주기적으로 실행한다`() {
        verify(expireReservationHoldsUseCase, timeout(3000).atLeast(2)).expireHolds()
    }

    @Configuration(proxyBeanMethods = false)
    class TestBeans {
        @Bean
        fun expireReservationHoldsUseCase(): ExpireReservationHoldsUseCase = mock(ExpireReservationHoldsUseCase::class.java).also {
            `when`(it.expireHolds()).thenReturn(ExpireReservationHoldsResult(0, 0, 0))
        }
    }
}
