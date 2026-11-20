package me.park.openrush.quota.application

import me.park.openrush.concert.application.provided.GetConcertUseCase
import me.park.openrush.quota.application.provided.AllocatePurchaseQuotaUseCase
import me.park.openrush.quota.application.provided.AllocateQuotaCommand
import me.park.openrush.quota.application.provided.AllocateQuotaResult
import me.park.openrush.quota.application.provided.ReleasePurchaseQuotaUseCase
import me.park.openrush.quota.application.required.PurchaseQuotaRepositoryPort
import me.park.openrush.quota.domain.exception.QuotaRuleViolationException
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Clock

@Service
class QuotaService(
    private val purchaseQuotaRepository: PurchaseQuotaRepositoryPort,
    private val getConcertUseCase: GetConcertUseCase,
    private val clock: Clock
) : AllocatePurchaseQuotaUseCase, ReleasePurchaseQuotaUseCase {
    @Transactional
    override fun allocate(allocateQuotaCommand: AllocateQuotaCommand): AllocateQuotaResult {
        val maxTicketsPerUser =
            getConcertUseCase.findConcert(allocateQuotaCommand.concertId)?.maxTicketsPerUser ?: throw QuotaRuleViolationException("공연이 존재하지 않습니다.")
        val purchaseQuotaId = purchaseQuotaRepository.findQuotaId(allocateQuotaCommand.userId, allocateQuotaCommand.concertId) ?: purchaseQuotaRepository.createQuota(
            allocateQuotaCommand.userId,
            allocateQuotaCommand.concertId
        )
        if (purchaseQuotaRepository.increaseIfWithinLimit(purchaseQuotaId, allocateQuotaCommand.quantity, maxTicketsPerUser, clock.instant()) != 1L) {
            throw QuotaRuleViolationException("콘서트별 1인 ${maxTicketsPerUser}매 한도를 초과했습니다.")
        }
        return AllocateQuotaResult(allocateQuotaCommand.quantity)
    }

    @Transactional
    override fun releaseQuota(userId: Long, concertId: Long, quantity: Int) {
        require(userId > 0 && concertId > 0 && quantity > 0)
        check(
            purchaseQuotaRepository.decreaseIfAllocated(
                userId,
                concertId,
                quantity,
                clock.instant()
            ) == 1L
        ) { "예매가 확보한 구매 한도를 반환하지 못했습니다." }
    }
}
