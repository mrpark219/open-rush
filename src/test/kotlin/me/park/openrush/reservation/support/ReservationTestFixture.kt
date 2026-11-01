package me.park.openrush.reservation.support

import org.springframework.jdbc.core.JdbcTemplate

class ReservationTestFixture(private val jdbc: JdbcTemplate) {
    fun create() {
        val concertId = id("insert into concerts (title, description, max_tickets_per_user) values ('test', '', 4) returning id")

        performanceId = createPerformance(concertId)
        otherPerformanceId = createPerformance(concertId)
        seatIds = createSeats(performanceId)
        otherSeatIds = createSeats(otherPerformanceId)
    }

    private fun createPerformance(concertId: Long): Long = id(
        """
        insert into performances (concert_id, venue, starts_at, booking_opens_at, booking_closes_at)
        values ($concertId, 'test', now() + interval '2 days', now() - interval '1 day', now() + interval '1 day')
        returning id
        """.trimIndent(),
    )

    private fun createSeats(performanceId: Long): List<Long> = (1..24).map { number ->
        id("""
            insert into performance_seats (performance_id, seat_number, price, status)
            values ($performanceId, 'A-$number', 50000, 'AVAILABLE') returning id
        """.trimIndent())
    }

    private fun id(sql: String): Long = checkNotNull(jdbc.queryForObject(sql, Long::class.java))

    var performanceId: Long = 0
        private set
    var otherPerformanceId: Long = 0
        private set
    lateinit var seatIds: List<Long>
        private set
    lateinit var otherSeatIds: List<Long>
        private set
}
