package me.park.openrush.reservation.domain

import me.park.openrush.common.domain.value.Money
import me.park.openrush.reservation.domain.seat.PerformanceSeat
import jakarta.persistence.*
import me.park.openrush.common.domain.BaseEntity

@Entity
@Table(
    name = "reservation_items", uniqueConstraints = [
        UniqueConstraint(name = "uk_reservation_item_seat", columnNames = ["reservation_id", "performance_seat_id"]),
    ]
)
class ReservationItem internal constructor(reservation: Reservation, seat: PerformanceSeat) : BaseEntity() {
    @field:ManyToOne(fetch = FetchType.LAZY, optional = false)
    @field:JoinColumn(name = "reservation_id", nullable = false)
    var reservation: Reservation = reservation
        protected set

    @field:ManyToOne(fetch = FetchType.LAZY, optional = false)
    @field:JoinColumn(name = "performance_seat_id", nullable = false)
    var seat: PerformanceSeat = seat
        protected set

    @field:Embedded
    @field:AttributeOverride(name = "amount", column = Column(name = "price", nullable = false))
    var price: Money = Money(seat.price.amount)
        protected set

}
