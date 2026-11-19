package me.park.openrush.inventory.application

import me.park.openrush.inventory.application.provided.*
import me.park.openrush.inventory.application.required.InventoryRepositoryPort
import me.park.openrush.inventory.domain.exception.InventoryRuleViolationException
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Clock
import java.time.Instant

@Service
class InventoryService(private val inventory: InventoryRepositoryPort, private val clock: Clock) :
    GetSeatsUseCase, HoldSeatsUseCase, ConfirmSeatsUseCase, ReleaseSeatsUseCase {
    @Transactional(readOnly = true)
    override fun findSeats(performanceId: Long, seatIds: List<Long>): List<SeatDetails> =
        inventory.findSeats(performanceId, seatIds).map {
            SeatDetails(requireNotNull(it.id), it.performanceId, it.amount, it.status)
        }

    @Transactional
    override fun hold(command: HoldSeatsCommand): HoldSeatsResult {
        if (inventory.holdAvailableSeats(
                command.performanceId, command.seatIds, command.reservationId, clock.instant()
            ) != command.seatIds.size.toLong()
        ) {
            throw InventoryRuleViolationException("요청한 좌석이 없거나 이미 점유되었습니다.")
        }
        return HoldSeatsResult(command.seatIds.sorted())
    }

    @Transactional
    override fun confirmSeats(reservationId: Long, seatIds: List<Long>, confirmedAt: Instant) {
        validate(reservationId, seatIds)
        check(
            inventory.confirmHeldSeats(
                reservationId, seatIds, confirmedAt
            ) == seatIds.size.toLong()
        ) { "예매가 선점한 좌석을 모두 확정하지 못했습니다." }
    }

    @Transactional
    override fun releaseSeats(reservationId: Long, seatIds: List<Long>) {
        validate(reservationId, seatIds)
        check(
            inventory.releaseHeldSeats(
                reservationId, seatIds, clock.instant()
            ) == seatIds.size.toLong()
        ) { "예매가 선점한 좌석을 모두 반환하지 못했습니다." }
    }

    private fun validate(reservationId: Long, seatIds: List<Long>) {
        require(reservationId > 0 && seatIds.isNotEmpty() && seatIds.all { it > 0 })
        require(seatIds.distinct().size == seatIds.size)
    }
}
