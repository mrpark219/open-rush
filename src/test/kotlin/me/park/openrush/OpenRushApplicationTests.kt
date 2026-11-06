package me.park.openrush

import org.junit.jupiter.api.Test
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import

@Import(TestcontainersConfiguration::class)
@SpringBootTest(properties = ["reservation.expiration.enabled=false"])
class OpenRushApplicationTests {

    @Test
    fun contextLoads() {
    }

}
