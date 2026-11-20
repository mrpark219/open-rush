package me.park.openrush.inventory.adapter.output.persistence
import me.park.openrush.inventory.adapter.output.persistence.repository.SeatRepository
import me.park.openrush.inventory.application.required.InventoryRepositoryPort
import me.park.openrush.inventory.domain.PerformanceSeat
import org.springframework.stereotype.Repository
import java.time.Instant

@Repository
class InventoryPersistenceAdapter(private val seatRepository: SeatRepository) : InventoryRepositoryPort {
    override fun findSeats(performanceId: Long, seatIds: List<Long>): List<PerformanceSeat> = seatRepository.findSeats(performanceId, seatIds)
    override fun holdAvailableSeats(performanceId: Long, seatIds: List<Long>, reservationId: Long, modifiedAt: Instant): Long = seatRepository.holdAvailableSeats(performanceId, seatIds, reservationId, modifiedAt)
    override fun confirmHeldSeats(reservationId: Long, seatIds: List<Long>, modifiedAt: Instant): Long = seatRepository.confirmHeldSeats(reservationId, seatIds, modifiedAt)
    override fun releaseHeldSeats(reservationId: Long, seatIds: List<Long>, modifiedAt: Instant): Long = seatRepository.releaseHeldSeats(reservationId, seatIds, modifiedAt)
}
