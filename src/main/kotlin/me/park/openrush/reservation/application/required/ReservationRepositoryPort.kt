package me.park.openrush.reservation.application.required

import me.park.openrush.reservation.application.provided.CreateReservationResult
import me.park.openrush.reservation.domain.Reservation
import java.time.Instant

interface ReservationRepositoryPort {
    /** 예약과 항목을 조회한다. 다른 도메인의 엔티티는 조회하지 않는다. */
    fun findReservation(reservationId: Long): Reservation?

    /** 호출자의 트랜잭션에서 예약과 항목을 저장한다. */
    fun save(reservation: Reservation): Reservation

    /** 소유자와 전달된 시각의 선점 기한을 검사하여 HELD 예약을 취소한다. */
    fun cancelHeld(reservationId: Long, userId: Long, now: Instant)

    /** 소유자와 전달된 시각의 선점 기한을 검사하여 HELD 예약을 확정한다. */
    fun confirmHeld(reservationId: Long, userId: Long, now: Instant)

    /** 기한이 지난 HELD 예약을 만료시킨다. 전환한 요청만 true를 반환한다. */
    fun expireHeld(reservationId: Long, now: Instant): Boolean

    /** 만료 대상 ID를 afterId 초과부터 오름차순으로 최대 limit건 조회한다. */
    fun findExpiredReservationIds(now: Instant, afterId: Long, limit: Int): List<Long>

    /** 호출자의 트랜잭션이 끝날 때까지 예약을 쓰기 잠금으로 조회한다. */
    fun lockReservation(reservationId: Long): Reservation?

    /** 소유자가 일치하는 예약만 쓰기 잠금으로 조회한다. */
    fun lockOwnedReservation(reservationId: Long, userId: Long): Reservation?

    /** 같은 사용자와 멱등 키로 완료한 예약 생성 결과를 조회한다. */
    fun findCreationRecord(userId: Long, key: String): ReservationCreationRecord?

    /** 현재 트랜잭션에서 처리할 예약 생성 요청의 멱등 키를 확보한다. */
    fun claimCreationRequest(userId: Long, key: String, request: String): Boolean

    /** 확보한 멱등 키에 생성 결과를 저장한다. */
    fun completeCreationRequest(userId: Long, key: String, result: CreateReservationResult)
}
