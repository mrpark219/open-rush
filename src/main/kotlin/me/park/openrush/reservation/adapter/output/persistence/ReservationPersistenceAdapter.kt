package me.park.openrush.reservation.adapter.output.persistence

import me.park.openrush.reservation.adapter.output.persistence.repository.ReservationRepository
import me.park.openrush.reservation.application.provided.CreateReservationResult
import me.park.openrush.reservation.application.required.ReservationCreationRecord
import me.park.openrush.reservation.application.required.ReservationRepositoryPort
import me.park.openrush.reservation.domain.Reservation
import me.park.openrush.reservation.domain.exception.ReservationRuleViolationException
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional
import java.time.Instant

@Component
class ReservationPersistenceAdapter(
    private val reservationRepository: ReservationRepository,
) : ReservationRepositoryPort {
    @Transactional(readOnly = true)
    override fun findExpiredReservationIds(now: Instant, afterId: Long, limit: Int): List<Long> {
        require(afterId >= 0 && limit > 0)
        return reservationRepository.findExpiredReservationIds(now, afterId, limit)
    }

    override fun expireHeld(reservationId: Long, now: Instant): Boolean =
        reservationRepository.expireIfHeld(reservationId, now) == 1L

    override fun findReservation(reservationId: Long): Reservation? =
        reservationRepository.findDetailedById(reservationId)

    override fun confirmHeld(reservationId: Long, userId: Long, now: Instant) {
        if (reservationRepository.confirmIfHeld(reservationId, userId, now) != 1L) {
            throw ReservationRuleViolationException("확정 가능한 선점 예매가 아닙니다.")
        }
    }

    override fun cancelHeld(reservationId: Long, userId: Long, now: Instant) {
        if (reservationRepository.cancelIfHeld(reservationId, userId, now) != 1L) {
            throw ReservationRuleViolationException("취소 가능한 선점 예매가 아닙니다.")
        }
    }

    override fun save(reservation: Reservation): Reservation = reservationRepository.save(reservation)

    override fun lockReservation(reservationId: Long): Reservation? =
        reservationRepository.lockReservation(reservationId)

    override fun lockOwnedReservation(reservationId: Long, userId: Long): Reservation? =
        reservationRepository.lockOwnedReservation(reservationId, userId)

    override fun findCreationRecord(userId: Long, key: String): ReservationCreationRecord? =
        reservationRepository.findCreationRecord(userId, key)

    override fun claimCreationRequest(userId: Long, key: String, request: String): Boolean =
        reservationRepository.claimCreationRequest(userId, key, request)

    override fun completeCreationRequest(userId: Long, key: String, result: CreateReservationResult) {
        reservationRepository.completeCreationRequest(userId, key, result)
    }
}
