package me.park.openrush.reservation.adapter.output.persistence.repository

import com.querydsl.jpa.impl.JPAQueryFactory
import jakarta.persistence.EntityManager
import jakarta.persistence.LockModeType
import me.park.openrush.reservation.application.provided.CreateReservationResult
import me.park.openrush.reservation.application.required.ReservationCreationRecord
import org.springframework.jdbc.core.JdbcTemplate
import tools.jackson.databind.ObjectMapper
import me.park.openrush.reservation.domain.QReservation.reservation
import me.park.openrush.reservation.domain.QReservationItem.reservationItem
import me.park.openrush.reservation.domain.Reservation
import me.park.openrush.reservation.domain.ReservationStatus
import org.springframework.stereotype.Repository
import java.time.Instant

@Repository
class ReservationRepositoryCustomImpl(
    private val jpaQueryFactory: JPAQueryFactory,
    private val entityManager: EntityManager,
    private val jdbcTemplate: JdbcTemplate,
    private val objectMapper: ObjectMapper,
) : ReservationRepositoryCustom {
    override fun findExpiredReservationIds(now: Instant, afterId: Long, limit: Int): List<Long> = jpaQueryFactory
        .select(reservation.id)
        .from(reservation)
        .where(
            reservation.status.eq(ReservationStatus.HELD),
            reservation.expiresAt.loe(now),
            reservation.id.gt(afterId),
        )
        .orderBy(reservation.id.asc())
        .limit(limit.toLong())
        .fetch()

    override fun expireIfHeld(reservationId: Long, now: Instant): Long {
        val updatedRowCount = jpaQueryFactory.update(reservation)
            .set(reservation.status, ReservationStatus.EXPIRED)
            .set(reservation.modifiedAt, now)
            .where(
                reservation.id.eq(reservationId),
                reservation.status.eq(ReservationStatus.HELD),
                reservation.expiresAt.loe(now),
            )
            .execute()
        entityManager.detach(entityManager.getReference(Reservation::class.java, reservationId))
        return updatedRowCount
    }

    override fun findDetailedById(id: Long): Reservation? = jpaQueryFactory
        .selectFrom(reservation)
        .distinct()
        .leftJoin(reservation.reservationItems, reservationItem).fetchJoin()
        .where(reservation.id.eq(id))
        .fetchOne()

    override fun confirmIfHeld(reservationId: Long, userId: Long, now: Instant): Long {
        val updatedRowCount = jpaQueryFactory.update(reservation)
            .set(reservation.status, ReservationStatus.CONFIRMED)
            .set(reservation.confirmedAt, now)
            .set(reservation.modifiedAt, now)
            .where(
                reservation.id.eq(reservationId),
                reservation.userId.eq(userId),
                reservation.status.eq(ReservationStatus.HELD),
                reservation.heldAt.loe(now),
                reservation.expiresAt.gt(now),
            )
            .execute()
        entityManager.detach(entityManager.getReference(Reservation::class.java, reservationId))
        return updatedRowCount
    }

    override fun cancelIfHeld(reservationId: Long, userId: Long, now: Instant): Long {
        val updatedRowCount = jpaQueryFactory.update(reservation)
            .set(reservation.status, ReservationStatus.CANCELLED)
            .set(reservation.modifiedAt, now)
            .where(
                reservation.id.eq(reservationId),
                reservation.userId.eq(userId),
                reservation.status.eq(ReservationStatus.HELD),
                reservation.heldAt.loe(now),
                reservation.expiresAt.gt(now),
            )
            .execute()
        entityManager.detach(entityManager.getReference(Reservation::class.java, reservationId))
        return updatedRowCount
    }

    override fun lockReservation(reservationId: Long): Reservation? = jpaQueryFactory.selectFrom(reservation)
        .where(reservation.id.eq(reservationId)).setLockMode(LockModeType.PESSIMISTIC_WRITE).fetchOne()

    override fun lockOwnedReservation(reservationId: Long, userId: Long): Reservation? = jpaQueryFactory.selectFrom(reservation)
        .where(reservation.id.eq(reservationId), reservation.userId.eq(userId)).setLockMode(LockModeType.PESSIMISTIC_WRITE).fetchOne()

    override fun configureLockTimeout(timeoutMillis: Int) {
        // 트랜잭션에만 적용하여 커밋·롤백 후 풀 연결에 설정을 남기지 않는다.
        entityManager.createNativeQuery("SELECT set_config('lock_timeout', :timeout, true)")
            .setParameter("timeout", "${timeoutMillis}ms")
            .singleResult
    }

    override fun findCreationRecord(userId: Long, key: String): ReservationCreationRecord? = jdbcTemplate.query(
        """
        SELECT request_payload, response FROM reservation_creation_records
        WHERE user_id = ? AND request_key = ? AND response IS NOT NULL
        """.trimIndent(),
        { resultSet, _ ->
            ReservationCreationRecord(
                resultSet.getString("request_payload"),
                objectMapper.readValue(resultSet.getString("response"), CreateReservationResult::class.java),
            )
        },
        userId, key,
    ).singleOrNull()

    override fun claimCreationRequest(userId: Long, key: String, request: String): Boolean = jdbcTemplate.update(
        """
        INSERT INTO reservation_creation_records (user_id, request_key, request_payload)
        VALUES (?, ?, ?)
        ON CONFLICT (user_id, request_key) DO NOTHING
        """.trimIndent(),
        userId, key, request,
    ) == 1

    override fun completeCreationRequest(userId: Long, key: String, result: CreateReservationResult) {
        val updatedRowCount = jdbcTemplate.update(
            """
            UPDATE reservation_creation_records
            SET reservation_id = ?, response = CAST(? AS jsonb)
            WHERE user_id = ? AND request_key = ? AND response IS NULL
            """.trimIndent(),
            result.reservationId, objectMapper.writeValueAsString(result), userId, key,
        )
        check(updatedRowCount == 1) { "처리 중인 예매 요청 기록을 찾을 수 없습니다." }
    }
}
