package me.park.openrush.reservation.adapter.output.persistence.repository

import com.querydsl.jpa.impl.JPAQueryFactory
import jakarta.persistence.EntityManager
import me.park.openrush.concert.domain.Concert
import me.park.openrush.reservation.domain.ConcertPurchaseQuota
import me.park.openrush.reservation.domain.QConcertPurchaseQuota.concertPurchaseQuota
import org.springframework.stereotype.Repository
import java.time.Instant

@Repository
class PurchaseQuotaRepositoryCustomImpl(
    private val queryFactory: JPAQueryFactory,
    private val entityManager: EntityManager,
) : PurchaseQuotaRepositoryCustom {
    override fun decreaseIfAllocated(userId: Long, concertId: Long, quantity: Int, modifiedAt: Instant): Long {
        val updated = queryFactory.update(concertPurchaseQuota)
            .set(concertPurchaseQuota.allocatedQuantity, concertPurchaseQuota.allocatedQuantity.subtract(quantity))
            .set(concertPurchaseQuota.modifiedAt, modifiedAt)
            .where(
                concertPurchaseQuota.userId.eq(userId),
                concertPurchaseQuota.concert.id.eq(concertId),
                concertPurchaseQuota.allocatedQuantity.goe(quantity),
            )
            .execute()
        findQuotaId(userId, concertId)?.let {
            entityManager.detach(entityManager.getReference(ConcertPurchaseQuota::class.java, it))
        }
        return updated
    }

    override fun findQuotaId(userId: Long, concertId: Long): Long? = queryFactory
        .select(concertPurchaseQuota.id)
        .from(concertPurchaseQuota)
        .where(
            concertPurchaseQuota.userId.eq(userId),
            concertPurchaseQuota.concert.id.eq(concertId),
        )
        .fetchOne()

    override fun createQuota(userId: Long, concertId: Long): Long {
        val concert = entityManager.getReference(Concert::class.java, concertId)
        val quota = ConcertPurchaseQuota(userId, concert)
        // 현재 IDENTITY 매핑에서는 persist 시 INSERT가 실행되어 ID와 생성 충돌이 확정된다.
        entityManager.persist(quota)
        return requireNotNull(quota.id)
    }

    override fun getMaxTicketsPerUser(concertId: Long): Int =
        entityManager.getReference(Concert::class.java, concertId).maxTicketsPerUser

    override fun increaseIfWithinLimit(
        quotaId: Long,
        quantity: Int,
        maxTicketsPerUser: Int,
        modifiedAt: Instant,
    ): Long = queryFactory.update(concertPurchaseQuota)
        .set(concertPurchaseQuota.allocatedQuantity, concertPurchaseQuota.allocatedQuantity.add(quantity))
        .set(concertPurchaseQuota.modifiedAt, modifiedAt)
        .where(
            concertPurchaseQuota.id.eq(quotaId),
            concertPurchaseQuota.allocatedQuantity.loe(maxTicketsPerUser - quantity),
        )
        .execute()
}
