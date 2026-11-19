package me.park.openrush.inventory.adapter.output.persistence
import me.park.openrush.inventory.adapter.output.persistence.repository.SeatRepository
import me.park.openrush.inventory.application.required.InventoryRepositoryPort
import me.park.openrush.inventory.domain.PerformanceSeat
import org.springframework.stereotype.Repository
import java.time.Instant

@Repository
class InventoryPersistenceAdapter(private val seats: SeatRepository) : InventoryRepositoryPort {
    override fun findSeats(performanceId: Long, seatIds: List<Long>): List<PerformanceSeat> = seats.findSeats(performanceId, seatIds)
    override fun holdAvailableSeats(performanceId: Long, seatIds: List<Long>, reservationId: Long, modifiedAt: Instant): Long = seats.holdAvailableSeats(performanceId, seatIds, reservationId, modifiedAt)
    override fun confirmHeldSeats(reservationId: Long, seatIds: List<Long>, modifiedAt: Instant): Long = seats.confirmHeldSeats(reservationId, seatIds, modifiedAt)
    override fun releaseHeldSeats(reservationId: Long, seatIds: List<Long>, modifiedAt: Instant): Long = seats.releaseHeldSeats(reservationId, seatIds, modifiedAt)
}
