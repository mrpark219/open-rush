package me.park.openrush.support.fixture

import org.springframework.jdbc.core.JdbcTemplate
import java.sql.Timestamp
import java.time.Instant
import java.time.temporal.ChronoUnit

class ConcertFixture(private val jdbcTemplate: JdbcTemplate) {
    fun createConcert(
        maxTicketsPerUser: Int = 4,
        title: String = "공연",
        description: String = "",
    ): Long = requireNotNull(
        jdbcTemplate.queryForObject(
            "INSERT INTO concerts(title, description, max_tickets_per_user) VALUES (?, ?, ?) RETURNING id",
            Long::class.java, title, description, maxTicketsPerUser,
        )
    )

    fun createPerformance(
        concertId: Long,
        startsAt: Instant = Instant.now().plus(2, ChronoUnit.DAYS),
        bookingOpensAt: Instant = Instant.now().minus(1, ChronoUnit.DAYS),
        bookingClosesAt: Instant = Instant.now().plus(1, ChronoUnit.DAYS),
        venue: String = "공연장",
    ): Long = requireNotNull(
        jdbcTemplate.queryForObject(
            """
            INSERT INTO performances(concert_id, venue, starts_at, booking_opens_at, booking_closes_at)
            VALUES (?, ?, ?, ?, ?) RETURNING id
            """.trimIndent(),
            Long::class.java, concertId, venue,
            Timestamp.from(startsAt), Timestamp.from(bookingOpensAt), Timestamp.from(bookingClosesAt),
        )
    )
}
