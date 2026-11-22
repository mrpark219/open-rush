package me.park.openrush.support.fixture

import org.springframework.jdbc.core.JdbcTemplate

class InventoryFixture(private val jdbcTemplate: JdbcTemplate) {
    fun createSeat(
        performanceId: Long,
        seatNumber: String = "A-1",
        amount: Long = 15_000,
    ): Long = requireNotNull(
        jdbcTemplate.queryForObject(
            """
            INSERT INTO performance_seats(performance_id, seat_number, price, status)
            VALUES (?, ?, ?, 'AVAILABLE') RETURNING id
            """.trimIndent(),
            Long::class.java, performanceId, seatNumber, amount,
        )
    )
}
