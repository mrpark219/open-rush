package me.park.openrush.inventory.domain

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class PerformanceSeatTest {
    @Test
    fun `좌석 금액은 음수일 수 없다`() {
        assertFailsWith<IllegalArgumentException> {
            PerformanceSeat(1, "A-1", -1)
        }
    }

    @Test
    fun `0원 좌석을 생성할 수 있다`() {
        assertEquals(0L, PerformanceSeat(1, "A-1", 0).amount)
    }
}
