package me.park.openrush.reservation.application

import me.park.openrush.reservation.application.port.input.ExpireReservationHoldUseCase
import me.park.openrush.reservation.application.port.input.ExpireReservationHoldsResult
import me.park.openrush.reservation.application.port.input.ExpireReservationHoldsUseCase
import me.park.openrush.reservation.application.port.output.FindExpiredReservationHoldsPort
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Service
import java.time.Clock

@Service
class ExpireReservationHoldsService(
    private val findExpiredReservationHoldsPort: FindExpiredReservationHoldsPort,
    private val expireReservationHoldUseCase: ExpireReservationHoldUseCase,
    private val clock: Clock,
    @param:Value("\${reservation.expiration.batch-size:100}") private val batchSize: Int,
) : ExpireReservationHoldsUseCase {
    private val logger = LoggerFactory.getLogger(javaClass)

    init {
        require(batchSize > 0) { "만료 조회 크기는 양수여야 합니다." }
    }

    override fun expireHolds(): ExpireReservationHoldsResult {
        val cutoff = clock.instant()
        var afterId = 0L
        var expired = 0
        var skipped = 0
        var failed = 0
        while (true) {
            val ids = findExpiredReservationHoldsPort.findExpiredReservationIds(cutoff, afterId, batchSize)
            if (ids.isEmpty()) break
            ids.forEach { id ->
                try {
                    if (expireReservationHoldUseCase.expireHold(id)) expired++ else skipped++
                } catch (exception: RuntimeException) {
                    failed++
                    logger.error("예매 만료 실패: reservationId={}. 다음 실행에서 재처리합니다.", id, exception)
                }
            }
            // 실패한 행도 지나가므로 뒤의 예매를 막지 않는다. 다음 실행에서는 처음부터 재조회한다.
            afterId = ids.last()
        }
        return ExpireReservationHoldsResult(expired, skipped, failed)
    }
}
