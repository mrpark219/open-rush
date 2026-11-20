package me.park.openrush.reservation.application

import me.park.openrush.reservation.application.provided.ExpireReservationHoldUseCase
import me.park.openrush.reservation.application.provided.ExpireReservationHoldsResult
import me.park.openrush.reservation.application.provided.ExpireReservationHoldsUseCase
import me.park.openrush.reservation.application.required.ReservationRepositoryPort
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Service
import java.time.Clock

@Service
class ExpireReservationHoldsService(
    private val reservationRepository: ReservationRepositoryPort,
    private val expireReservationHoldUseCase: ExpireReservationHoldUseCase,
    private val clock: Clock,
    @param:Value("\${reservation.expiration.batch-size:100}") private val batchSize: Int,
) : ExpireReservationHoldsUseCase {
    private val logger = LoggerFactory.getLogger(javaClass)

    init {
        require(batchSize > 0) { "만료 조회 크기는 양수여야 합니다." }
    }

    override fun expireHolds(): ExpireReservationHoldsResult {
        val expirationCutoff = clock.reservationTimestamp()
        var afterId = 0L
        var expiredReservationCount = 0
        var skippedReservationCount = 0
        var failedReservationCount = 0
        while (true) {
            val reservationIds = reservationRepository.findExpiredReservationIds(expirationCutoff, afterId, batchSize)
            if (reservationIds.isEmpty()) break
            reservationIds.forEach { reservationId ->
                try {
                    if (expireReservationHoldUseCase.expireHold(reservationId)) expiredReservationCount++ else skippedReservationCount++
                } catch (exception: RuntimeException) {
                    failedReservationCount++
                    logger.error("예매 만료 실패: reservationId={}. 다음 실행에서 재처리합니다.", reservationId, exception)
                }
            }
            // 실패한 행도 지나가므로 뒤의 예매를 막지 않는다. 다음 실행에서는 처음부터 재조회한다.
            afterId = reservationIds.last()
        }
        return ExpireReservationHoldsResult(expiredReservationCount, skippedReservationCount, failedReservationCount)
    }
}
