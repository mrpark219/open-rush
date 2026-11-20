package me.park.openrush.reservation.adapter.output.persistence

import me.park.openrush.reservation.adapter.output.persistence.repository.ReservationRepository
import me.park.openrush.reservation.application.required.ConfigureExpirationTransactionPort
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional

@Component
class ExpirationTransactionPersistenceAdapter(
    private val reservationRepository: ReservationRepository,
    @param:Value("\${reservation.expiration.lock-timeout-ms:1000}") private val lockTimeoutMillis: Int,
) : ConfigureExpirationTransactionPort {
    init {
        require(lockTimeoutMillis > 0) { "만료 잠금 대기 시간은 양수여야 합니다." }
    }

    @Transactional(propagation = Propagation.MANDATORY)
    override fun configure() {
        reservationRepository.configureLockTimeout(lockTimeoutMillis)
    }
}
