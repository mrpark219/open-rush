package me.park.openrush.reservation.domain

import jakarta.persistence.*
import me.park.openrush.concert.domain.Performance

@Entity
@Table(
    name = "performance_seats", uniqueConstraints = [
        UniqueConstraint(name = "uk_performance_seat_number", columnNames = ["performance_id", "seat_number"]),
    ]
)
class PerformanceSeat(performance: Performance, seatNumber: SeatNumber, price: Money) {
    @field:Id
    @field:GeneratedValue(strategy = GenerationType.IDENTITY)
    var id: Long? = null
        protected set

    @field:ManyToOne(fetch = FetchType.LAZY, optional = false)
    @field:JoinColumn(name = "performance_id", nullable = false)
    var performance: Performance = performance
        protected set

    @field:Embedded
    var seatNumber: SeatNumber = seatNumber
        protected set

    @field:Embedded
    @field:AttributeOverride(name = "amount", column = Column(name = "price", nullable = false))
    var price: Money = price
        protected set

    @field:Enumerated(EnumType.STRING)
    @field:Column(nullable = false)
    var status: SeatStatus = SeatStatus.AVAILABLE
        protected set

    @field:Column(name = "reservation_id")
    var reservationId: Long? = null
        protected set

    fun hold(reservationId: Long) {
        require(reservationId > 0) { "저장된 예매 ID가 필요합니다." }
        checkRule(status == SeatStatus.AVAILABLE, "이미 점유된 좌석입니다.")
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
        checkRule(
            status == SeatStatus.HELD && this.reservationId == reservationId,
            "현재 선점한 예매만 좌석을 변경할 수 있습니다."
        )
    }
}
