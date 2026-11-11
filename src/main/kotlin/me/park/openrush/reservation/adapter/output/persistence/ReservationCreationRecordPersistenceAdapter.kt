package me.park.openrush.reservation.adapter.output.persistence

import me.park.openrush.reservation.application.port.input.CreateReservationResult
import me.park.openrush.reservation.application.port.output.ReservationCreationRecord
import me.park.openrush.reservation.application.port.output.ReservationCreationRecordPort
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Repository
import tools.jackson.databind.ObjectMapper

@Repository
class ReservationCreationRecordPersistenceAdapter(
    private val jdbc: JdbcTemplate,
    private val json: ObjectMapper,
) : ReservationCreationRecordPort {
    override fun find(userId: Long, key: String): ReservationCreationRecord? = jdbc.query(
        """
        SELECT request_payload, response FROM reservation_creation_records
        WHERE user_id = ? AND request_key = ? AND response IS NOT NULL
        """.trimIndent(),
        { row, _ ->
            ReservationCreationRecord(
                row.getString("request_payload"),
                json.readValue(row.getString("response"), CreateReservationResult::class.java),
            )
        },
        userId, key,
    ).singleOrNull()

    override fun claim(userId: Long, key: String, request: String): Boolean = jdbc.update(
        """
        INSERT INTO reservation_creation_records (user_id, request_key, request_payload)
        VALUES (?, ?, ?)
        ON CONFLICT (user_id, request_key) DO NOTHING
        """.trimIndent(),
        userId, key, request,
    ) == 1

    override fun complete(userId: Long, key: String, result: CreateReservationResult) {
        val updated = jdbc.update(
            """
            UPDATE reservation_creation_records
            SET reservation_id = ?, response = CAST(? AS jsonb)
            WHERE user_id = ? AND request_key = ? AND response IS NULL
            """.trimIndent(),
            result.reservationId, json.writeValueAsString(result), userId, key,
        )
        check(updated == 1) { "처리 중인 예매 요청 기록을 찾을 수 없습니다." }
    }
}
