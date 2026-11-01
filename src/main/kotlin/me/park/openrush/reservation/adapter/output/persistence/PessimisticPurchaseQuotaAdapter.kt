package me.park.openrush.reservation.adapter.output.persistence

import com.querydsl.jpa.impl.JPAQueryFactory
import jakarta.persistence.EntityManager
import jakarta.persistence.LockModeType
import me.park.openrush.reservation.application.exception.PurchaseQuotaCreationConflict
import me.park.openrush.reservation.application.port.output.AllocatePurchaseQuotaPort
import me.park.openrush.reservation.application.port.output.AllocateQuotaCommand
import me.park.openrush.reservation.application.port.output.AllocateQuotaResult
import me.park.openrush.reservation.domain.ConcertPurchaseQuota
import me.park.openrush.reservation.domain.QConcertPurchaseQuota.concertPurchaseQuota
import me.park.openrush.reservation.domain.exception.ReservationRuleViolation
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.stereotype.Repository
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional

@Repository
@ConditionalOnProperty(name = ["reservation.concurrency-strategy"], havingValue = "pessimistic", matchIfMissing = true)
@Transactional(propagation = Propagation.MANDATORY)
class PessimisticPurchaseQuotaAdapter(
    private val queryFactory: JPAQueryFactory,
    private val entityManager: EntityManager,
    private val purchaseQuotaCreator: PurchaseQuotaCreator,
) : AllocatePurchaseQuotaPort {
    @Throws(ReservationRuleViolation::class, PurchaseQuotaCreationConflict::class)
    override fun allocate(command: AllocateQuotaCommand): AllocateQuotaResult {
        val quota = findQuotaForUpdate(command) ?: purchaseQuotaCreator.create(command)

        quota.allocate(command.quantity)
        entityManager.flush()

        return AllocateQuotaResult(quantity = command.quantity)
    }

    private fun findQuotaForUpdate(command: AllocateQuotaCommand): ConcertPurchaseQuota? {
        val quota = queryFactory
            .selectFrom(concertPurchaseQuota)
            .where(
                concertPurchaseQuota.userId.eq(command.userId),
                concertPurchaseQuota.concert.id.eq(command.concertId),
            )
            .fetchOne()

        quota?.let { entityManager.refresh(it, LockModeType.PESSIMISTIC_WRITE) }

        return quota
    }
}
