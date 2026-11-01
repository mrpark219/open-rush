package me.park.openrush.reservation.adapter.output.persistence

import jakarta.persistence.EntityManager
import jakarta.persistence.PersistenceException
import me.park.openrush.concert.domain.Concert
import me.park.openrush.reservation.application.exception.PurchaseQuotaCreationConflict
import me.park.openrush.reservation.application.port.output.AllocateQuotaCommand
import me.park.openrush.reservation.domain.ConcertPurchaseQuota
import org.hibernate.exception.ConstraintViolationException
import org.springframework.stereotype.Repository
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional

@Repository
@Transactional(propagation = Propagation.MANDATORY)
class PurchaseQuotaCreator(private val entityManager: EntityManager) {
    @Throws(PurchaseQuotaCreationConflict::class)
    fun create(command: AllocateQuotaCommand): ConcertPurchaseQuota {
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
