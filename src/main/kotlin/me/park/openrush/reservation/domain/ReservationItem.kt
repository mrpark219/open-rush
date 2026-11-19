package me.park.openrush.reservation.domain

import jakarta.persistence.*
import me.park.openrush.support.persistence.BaseEntity

@Entity
@Table(
    name = "reservation_items", uniqueConstraints = [
        UniqueConstraint(name = "uk_reservation_item_seat", columnNames = ["reservation_id", "performance_seat_id"]),
    ]
)
class ReservationItem internal constructor(reservation: Reservation, seat: ReservationSeat) : BaseEntity() {
    @field:ManyToOne(fetch = FetchType.LAZY, optional = false)
    @field:JoinColumn(name = "reservation_id", nullable = false)
    var reservation: Reservation = reservation
        protected set

    @field:Column(name = "performance_seat_id", nullable = false)
    var seatId: Long = requireNotNull(seat.id)
        protected set

    @field:Column(name = "price", nullable = false)
    var amount: Long = seat.amount
        protected set

}
