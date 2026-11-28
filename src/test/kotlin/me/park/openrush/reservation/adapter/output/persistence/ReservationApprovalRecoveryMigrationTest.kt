package me.park.openrush.reservation.adapter.output.persistence

import me.park.openrush.support.fixture.ConcertFixture
import org.flywaydb.core.Flyway
import org.junit.jupiter.api.Test
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.datasource.DriverManagerDataSource
import org.testcontainers.postgresql.PostgreSQLContainer
import org.testcontainers.utility.DockerImageName
import java.util.UUID
import kotlin.test.assertEquals

class ReservationApprovalRecoveryMigrationTest {
    @Test
    fun `미기록 주문을 복구 대상으로 옮기고 이미 기록된 승인 주문은 제외한다`() {
        PostgreSQLContainer(DockerImageName.parse("postgres:18")).use { postgres ->
            postgres.start()
            Flyway.configure().dataSource(postgres.jdbcUrl, postgres.username, postgres.password)
                .target("11").load().migrate()
            val jdbcTemplate = JdbcTemplate(DriverManagerDataSource(postgres.jdbcUrl, postgres.username, postgres.password))
            val concertFixture = ConcertFixture(jdbcTemplate)
            val performanceId = concertFixture.createPerformance(concertFixture.createConcert())
            val reservationId = requireNotNull(jdbcTemplate.queryForObject(
                """
                INSERT INTO reservations(performance_id, user_id, held_at, expires_at, status)
                VALUES (?, 1, now(), now() + interval '5 minutes', 'HELD') RETURNING id
                """.trimIndent(),
                Long::class.java, performanceId,
            ))

            fun prepare(orderNumber: String, paymentKey: String?, attemptNumber: UUID = UUID.randomUUID()) {
                jdbcTemplate.update(
                    """
                    INSERT INTO reservation_payment_tx(reservation_id, type, provider, amount, attempt_number,
                        pg_order_number, approval_idempotency_key, requested_payment_key)
                    VALUES (?, 'APPROVAL_PREPARED', 'demo-pg', 15000, ?, ?, ?, ?)
                    """.trimIndent(),
                    reservationId, attemptNumber, orderNumber, "confirm-$orderNumber", paymentKey,
                )
            }

            prepare("OR-PENDING", "PK-PENDING")
            prepare("OR-LEGACY", null)
            val approvedAttemptNumber = UUID.randomUUID()
            prepare("OR-APPROVED", "PK-APPROVED", approvedAttemptNumber)
            jdbcTemplate.update(
                """
                INSERT INTO reservation_payment_tx(reservation_id, type, provider, amount, attempt_number,
                    pg_order_number, payment_id, currency, status)
                VALUES (?, 'APPROVED', 'demo-pg', 15000, ?, 'OR-APPROVED', 'PK-APPROVED', 'KRW', 'RESERVATION_CONFIRMED')
                """.trimIndent(),
                reservationId, approvedAttemptNumber,
            )

            Flyway.configure().dataSource(postgres.jdbcUrl, postgres.username, postgres.password).load().migrate()

            val statuses = jdbcTemplate.query(
                """
                SELECT preparation.pg_order_number, job.status
                FROM reservation_approval_recovery_jobs job
                JOIN reservation_payment_tx preparation ON preparation.id = job.payment_preparation_id
                """.trimIndent(),
            ) { resultSet, _ -> resultSet.getString("pg_order_number") to resultSet.getString("status") }.toMap()
            assertEquals(mapOf("OR-PENDING" to "PENDING", "OR-LEGACY" to "NEEDS_REVIEW"), statuses)
        }
    }
}
