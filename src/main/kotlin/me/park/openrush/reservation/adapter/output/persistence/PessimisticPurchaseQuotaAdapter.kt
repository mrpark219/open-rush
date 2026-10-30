package me.park.openrush.reservation.adapter.output.persistence

import com.querydsl.jpa.impl.JPAQueryFactory
import jakarta.persistence.EntityManager
import jakarta.persistence.LockModeType
import jakarta.persistence.PersistenceException
import me.park.openrush.concert.domain.Concert
import me.park.openrush.reservation.application.exception.PurchaseQuotaCreationConflict
import me.park.openrush.reservation.application.port.output.AllocatePurchaseQuotaPort
import me.park.openrush.reservation.application.port.output.AllocateQuotaCommand
import me.park.openrush.reservation.application.port.output.AllocateQuotaResult
import me.park.openrush.reservation.domain.ConcertPurchaseQuota
import me.park.openrush.reservation.domain.QConcertPurchaseQuota.concertPurchaseQuota
import me.park.openrush.reservation.domain.exception.ReservationRuleViolation
import org.hibernate.exception.ConstraintViolationException
import org.springframework.stereotype.Repository
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional

@Repository
@Transactional(propagation = Propagation.MANDATORY)
class PessimisticPurchaseQuotaAdapter(
    private val queryFactory: JPAQueryFactory,
    private val entityManager: EntityManager,
) : AllocatePurchaseQuotaPort {
    @Throws(ReservationRuleViolation::class, PurchaseQuotaCreationConflict::class)
    override fun allocate(command: AllocateQuotaCommand): AllocateQuotaResult {
        val quota = findQuotaForUpdate(command) ?: createQuota(command)

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
            .setLockMode(LockModeType.PESSIMISTIC_WRITE)
            .fetchOne()

        quota?.let(entityManager::refresh)

        return quota
    }

    private fun createQuota(command: AllocateQuotaCommand): ConcertPurchaseQuota {
        val concert = entityManager.getReference(Concert::class.java, command.concertId)
        val quota = ConcertPurchaseQuota(command.userId, concert)

        try {
            entityManager.persist(quota)
            entityManager.flush()
        } catch (exception: PersistenceException) {
            if (isQuotaCreationConflict(exception)) {
                throw PurchaseQuotaCreationConflict(exception)
            }

            throw exception
        }

        return quota
    }

    private fun isQuotaCreationConflict(exception: PersistenceException): Boolean =
        generateSequence<Throwable>(exception) { it.cause }
            .filterIsInstance<ConstraintViolationException>()
            .any { it.constraintName == "uk_user_concert_quota" }
}
