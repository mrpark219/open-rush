package me.park.openrush.quota.adapter.output.persistence

import me.park.openrush.quota.adapter.output.persistence.repository.PurchaseQuotaRepository
import me.park.openrush.quota.application.exception.PurchaseQuotaCreationConflictException
import me.park.openrush.quota.application.required.PurchaseQuotaRepositoryPort
import org.hibernate.exception.ConstraintViolationException
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.stereotype.Repository
import java.time.Instant

@Repository
class QuotaPersistenceAdapter(private val quotas: PurchaseQuotaRepository) : PurchaseQuotaRepositoryPort {
    override fun findQuotaId(userId: Long, concertId: Long): Long? = quotas.findQuotaId(userId, concertId)
    override fun createQuota(userId: Long, concertId: Long): Long = try {
        quotas.createQuota(userId, concertId)
    } catch (exception: DataIntegrityViolationException) {
        if (generateSequence<Throwable>(exception) { it.cause }.filterIsInstance<ConstraintViolationException>()
                .any { it.constraintName == "uk_user_concert_quota" }
        ) throw PurchaseQuotaCreationConflictException(exception)
        throw exception
    }

    override fun increaseIfWithinLimit(
        quotaId: Long,
        quantity: Int,
        maxTicketsPerUser: Int,
        modifiedAt: Instant
    ): Long = quotas.increaseIfWithinLimit(quotaId, quantity, maxTicketsPerUser, modifiedAt)

    override fun decreaseIfAllocated(userId: Long, concertId: Long, quantity: Int, modifiedAt: Instant): Long =
        quotas.decreaseIfAllocated(userId, concertId, quantity, modifiedAt)
}
