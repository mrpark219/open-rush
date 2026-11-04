package me.park.openrush.reservation.adapter.output.persistence

import me.park.openrush.reservation.adapter.output.persistence.repository.PurchaseQuotaRepository
import me.park.openrush.reservation.application.exception.PurchaseQuotaCreationConflict
import me.park.openrush.reservation.application.port.output.AllocatePurchaseQuotaPort
import me.park.openrush.reservation.application.port.output.AllocateQuotaCommand
import me.park.openrush.reservation.application.port.output.AllocateQuotaResult
import me.park.openrush.reservation.domain.exception.ReservationRuleViolation
import org.hibernate.exception.ConstraintViolationException
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.stereotype.Component
import java.time.Clock

@Component
class PurchaseQuotaPersistenceAdapter(
    private val purchaseQuotaRepository: PurchaseQuotaRepository,
    private val clock: Clock,
) : AllocatePurchaseQuotaPort {
    override fun allocate(command: AllocateQuotaCommand): AllocateQuotaResult {
        val quotaId = purchaseQuotaRepository.findQuotaId(command.userId, command.concertId)
            ?: createQuota(command)
        val maxTickets = purchaseQuotaRepository.getMaxTicketsPerUser(command.concertId)

        val updated = purchaseQuotaRepository.increaseIfWithinLimit(
            quotaId = quotaId,
            quantity = command.quantity,
            maxTicketsPerUser = maxTickets,
            modifiedAt = clock.instant(),
        )

        if (updated != 1L) {
            throw ReservationRuleViolation("콘서트별 1인 ${maxTickets}매 한도를 초과했습니다.")
        }

        return AllocateQuotaResult(quantity = command.quantity)
    }

    private fun createQuota(command: AllocateQuotaCommand): Long =
        try {
            purchaseQuotaRepository.createQuota(command.userId, command.concertId)
        } catch (exception: DataIntegrityViolationException) {
            if (isQuotaCreationConflict(exception)) {
                throw PurchaseQuotaCreationConflict(exception)
            }

            throw exception
        }

    private fun isQuotaCreationConflict(exception: DataIntegrityViolationException): Boolean =
        generateSequence<Throwable>(exception) { it.cause }
            .filterIsInstance<ConstraintViolationException>()
            .any { it.constraintName == "uk_user_concert_quota" }
}
