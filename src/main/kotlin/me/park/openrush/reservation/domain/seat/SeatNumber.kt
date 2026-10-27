package me.park.openrush.reservation.domain.seat

import jakarta.persistence.Column
import jakarta.persistence.Embeddable

@Embeddable
class SeatNumber(value: String) {
    @field:Column(name = "seat_number", nullable = false)
    var value: String = value
        protected set

    init {
        require(value.isNotBlank()) { "좌석 번호는 비어 있을 수 없습니다." }
    }

    override fun equals(other: Any?) = other is SeatNumber && value == other.value
    override fun hashCode() = value.hashCode()
}
