package me.park.openrush.quota.adapter.output.persistence.repository

import com.querydsl.jpa.impl.JPAQueryFactory
import jakarta.persistence.EntityManager
import me.park.openrush.quota.domain.ConcertPurchaseQuota
import me.park.openrush.quota.domain.QConcertPurchaseQuota.concertPurchaseQuota
import org.springframework.stereotype.Repository
import java.time.Instant

@Repository
class PurchaseQuotaRepositoryCustomImpl(
    private val jpaQueryFactory: JPAQueryFactory,
    private val entityManager: EntityManager,
) : PurchaseQuotaRepositoryCustom {
    override fun decreaseIfAllocated(userId: Long, concertId: Long, quantity: Int, modifiedAt: Instant): Long {
        val updatedRowCount = jpaQueryFactory.update(concertPurchaseQuota)
            .set(concertPurchaseQuota.allocatedQuantity, concertPurchaseQuota.allocatedQuantity.subtract(quantity))
            .set(concertPurchaseQuota.modifiedAt, modifiedAt)
            .where(
                concertPurchaseQuota.userId.eq(userId),
                concertPurchaseQuota.concertId.eq(concertId),
                concertPurchaseQuota.allocatedQuantity.goe(quantity),
            )
            .execute()
        findQuotaId(userId, concertId)?.let {
            entityManager.detach(entityManager.getReference(ConcertPurchaseQuota::class.java, it))
        }
        return updatedRowCount
    }

    override fun findQuotaId(userId: Long, concertId: Long): Long? = jpaQueryFactory
        .select(concertPurchaseQuota.id)
        .from(concertPurchaseQuota)
        .where(
            concertPurchaseQuota.userId.eq(userId),
            concertPurchaseQuota.concertId.eq(concertId),
        )
        .fetchOne()

    override fun createQuota(userId: Long, concertId: Long): Long {
        val quota = ConcertPurchaseQuota(userId, concertId)
        // 현재 IDENTITY 매핑에서는 persist 시 INSERT가 실행되어 ID와 생성 충돌이 확정된다.
        entityManager.persist(quota)
        return requireNotNull(quota.id)
    }

    override fun increaseIfWithinLimit(
        quotaId: Long,
        quantity: Int,
        maxTicketsPerUser: Int,
        modifiedAt: Instant,
    ): Long = jpaQueryFactory.update(concertPurchaseQuota)
        .set(concertPurchaseQuota.allocatedQuantity, concertPurchaseQuota.allocatedQuantity.add(quantity))
        .set(concertPurchaseQuota.modifiedAt, modifiedAt)
        .where(
            concertPurchaseQuota.id.eq(quotaId),
            concertPurchaseQuota.allocatedQuantity.loe(maxTicketsPerUser - quantity),
        )
        .execute()
}
