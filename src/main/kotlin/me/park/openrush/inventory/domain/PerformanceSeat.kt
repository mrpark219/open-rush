package me.park.openrush.inventory.domain

import jakarta.persistence.*
import me.park.openrush.inventory.domain.exception.InventoryRuleViolationException
import me.park.openrush.support.persistence.BaseEntity

@Entity
@Table(
    name = "performance_seats", uniqueConstraints = [
        UniqueConstraint(name = "uk_performance_seat_number", columnNames = ["performance_id", "seat_number"]),
    ]
)
class PerformanceSeat(
    performanceId: Long,
    seatNumber: String,
    amount: Long
) : BaseEntity() {
    @field:Column(name = "performance_id", nullable = false)
    var performanceId: Long = performanceId
        protected set

    @field:Column(name = "seat_number", nullable = false)
    var seatNumber: String = seatNumber
        protected set

    @field:Column(name = "price", nullable = false)
    var amount: Long = amount
        protected set

    @field:Enumerated(EnumType.STRING)
    @field:Column(nullable = false)
    var status: SeatStatus = SeatStatus.AVAILABLE
        protected set

    @field:Column(name = "reservation_id")
    var reservationId: Long? = null
        protected set

    init {
        require(seatNumber.isNotBlank()) { "좌석 번호는 비어 있을 수 없습니다." }
        require(amount >= 0) { "금액은 음수일 수 없습니다." }
    }

    fun hold(reservationId: Long) {
        require(reservationId > 0) { "저장된 예매 ID가 필요합니다." }
        checkInventory(status == SeatStatus.AVAILABLE, "이미 점유된 좌석입니다.")
        this.reservationId = reservationId
        status = SeatStatus.HELD
    }

    fun confirm(reservationId: Long) {
        requireHeldBy(reservationId)
        status = SeatStatus.RESERVED
    }

    fun release(reservationId: Long) {
        requireHeldBy(reservationId)
        this.reservationId = null
        status = SeatStatus.AVAILABLE
    }

    private fun requireHeldBy(reservationId: Long) {
        checkInventory(
            status == SeatStatus.HELD && this.reservationId == reservationId,
            "현재 선점한 예매만 좌석을 변경할 수 있습니다."
        )
    }
}

private fun checkInventory(condition: Boolean, message: String) {
    if (!condition) {
        throw InventoryRuleViolationException(message)
    }
}
