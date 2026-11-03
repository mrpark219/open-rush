package me.park.openrush.reservation.adapter.output.persistence

import com.querydsl.jpa.impl.JPAQueryFactory
import jakarta.persistence.EntityManager
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
import java.time.Instant

@Repository
@Transactional(propagation = Propagation.MANDATORY)
class ConditionalUpdatePurchaseQuotaAdapter(
    private val queryFactory: JPAQueryFactory,
    private val entityManager: EntityManager,
) : AllocatePurchaseQuotaPort {
    @Throws(ReservationRuleViolation::class, PurchaseQuotaCreationConflict::class)
    override fun allocate(command: AllocateQuotaCommand): AllocateQuotaResult {
        val quota = findQuota(command) ?: createQuota(command)

        val maxTickets = quota.concert.maxTicketsPerUser
        entityManager.flush()
        // 벌크 UPDATE는 영속성 컨텍스트를 갱신하지 않으므로 기존 스냅샷을 분리한다.
        entityManager.detach(quota)

        val updated = queryFactory.update(concertPurchaseQuota)
            .set(concertPurchaseQuota.allocatedQuantity, concertPurchaseQuota.allocatedQuantity.add(command.quantity))
            .set(concertPurchaseQuota.modifiedAt, Instant.now())
            .where(
                concertPurchaseQuota.id.eq(requireNotNull(quota.id)),
                concertPurchaseQuota.allocatedQuantity.loe(maxTickets - command.quantity),
            )
            .execute()

        if (updated != 1L) {
            throw ReservationRuleViolation("콘서트별 1인 ${maxTickets}매 한도를 초과했습니다.")
        }

        return AllocateQuotaResult(quantity = command.quantity)
    }

    private fun findQuota(command: AllocateQuotaCommand): ConcertPurchaseQuota? =
        queryFactory
            .selectFrom(concertPurchaseQuota)
            .where(
                concertPurchaseQuota.userId.eq(command.userId),
                concertPurchaseQuota.concert.id.eq(command.concertId),
            )
            .fetchOne()

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
