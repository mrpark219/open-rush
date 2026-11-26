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

class ReservationRefundJobMigrationTest {
    @Test
    fun `기존 환불 필요 승인과 전액 취소 준비를 이전하고 완료된 환불은 대기 상태로 만들지 않는다`() {
        PostgreSQLContainer(DockerImageName.parse("postgres:18")).use { postgres ->
            postgres.start()
            Flyway.configure().dataSource(postgres.jdbcUrl, postgres.username, postgres.password)
                .target("9").load().migrate()
            val jdbcTemplate = JdbcTemplate(DriverManagerDataSource(postgres.jdbcUrl, postgres.username, postgres.password))
            val concertId = ConcertFixture(jdbcTemplate).createConcert()
            val performanceId = ConcertFixture(jdbcTemplate).createPerformance(concertId)
            val reservationId = jdbcTemplate.queryForObject(
                """
                INSERT INTO reservations(performance_id, user_id, held_at, expires_at, status)
                VALUES (?, 1, now(), now() + interval '5 minutes', 'HELD') RETURNING id
                """.trimIndent(),
                Long::class.java, performanceId,
            )!!

            fun approval(paymentId: String, amount: Long = 15000, refundRequired: Boolean = true): Long {
                val paymentStatus = if (refundRequired) {
                    "REFUND_REQUIRED"
                } else {
                    "RESERVATION_CONFIRMED"
                }
                val refundReason = if (refundRequired) {
                    "RESERVATION_UNAVAILABLE"
                } else {
                    null
                }

                return jdbcTemplate.queryForObject(
                    """
                    INSERT INTO reservation_payment_tx(reservation_id, type, provider, payment_id, amount, currency, status, refund_reason)
                    VALUES (?, 'APPROVED', 'demo-pg', ?, ?, 'KRW', ?, ?) RETURNING id
                    """.trimIndent(),
                    Long::class.java, reservationId, paymentId, amount,
                    paymentStatus, refundReason,
                )!!
            }

            fun cancellation(paymentId: String, completed: Boolean) {
                val cancellationNumber = UUID.nameUUIDFromBytes(paymentId.toByteArray())
                val transactionType = if (completed) {
                    "CANCELED"
                } else {
                    "CANCEL_PREPARED"
                }
                val cancellationId = if (completed) {
                    "CANCEL-$paymentId"
                } else {
                    null
                }

                jdbcTemplate.update(
                    """
                    INSERT INTO reservation_payment_tx(
                        reservation_id, type, provider, payment_id, amount, currency,
                        cancellation_number, cancellation_idempotency_key, cancel_reason, cancellation_id
                    ) VALUES (?, ?, 'demo-pg', ?, 15000, 'KRW', ?, ?, '취소', ?)
                    """.trimIndent(),
                    reservationId, transactionType, paymentId,
                    cancellationNumber, "cancel-$cancellationNumber", cancellationId,
                )
            }

            val pendingApprovalId = approval("PK-PENDING")
            val completedApprovalId = approval("PK-COMPLETED")
            cancellation("PK-COMPLETED", completed = true)
            val canceledReservationApprovalId = approval("PK-RESERVATION-CANCELED", refundRequired = false)
            cancellation("PK-RESERVATION-CANCELED", completed = false)
            approval("PK-NORMAL", refundRequired = false)
            approval("PK-ZERO", amount = 0)

            Flyway.configure().dataSource(postgres.jdbcUrl, postgres.username, postgres.password).load().migrate()

            val jobs = jdbcTemplate.queryForList(
                "SELECT approved_payment_transaction_id, status FROM reservation_refund_jobs",
            ).associate { row -> (row["approved_payment_transaction_id"] as Number).toLong() to row["status"] }
            assertEquals(mapOf(
                pendingApprovalId to "PENDING",
                completedApprovalId to "COMPLETED",
                canceledReservationApprovalId to "PENDING",
            ), jobs)
            assertEquals(1, jdbcTemplate.queryForObject(
                "SELECT count(*) FROM reservation_refund_jobs WHERE status = 'COMPLETED' AND completed_at IS NOT NULL",
                Int::class.java,
            ))
        }
    }
}
