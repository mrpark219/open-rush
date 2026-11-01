package me.park.openrush.reservation.support

import org.springframework.jdbc.core.JdbcTemplate
import org.testcontainers.postgresql.PostgreSQLContainer

class TestDatabaseCleaner(
    private val jdbc: JdbcTemplate,
    private val postgres: PostgreSQLContainer,
) {
    fun clear() {
        check(postgres.isRunning) { "실행 중인 테스트 DB가 필요합니다." }

        val actualUrl = checkNotNull(jdbc.dataSource).connection.use { it.metaData.url.substringBefore('?') }
        val testContainerUrl = postgres.jdbcUrl.substringBefore('?')
        check(actualUrl == testContainerUrl) { "Testcontainers DB만 초기화할 수 있습니다." }

        jdbc.execute(
            """
            truncate reservation_items, performance_seats, reservations,
                     concert_purchase_quotas, performances, concerts
            restart identity restrict
            """.trimIndent(),
        )
    }
}
