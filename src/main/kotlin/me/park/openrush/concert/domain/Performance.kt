package me.park.openrush.concert.domain

import jakarta.persistence.*
import me.park.openrush.common.domain.BaseEntity
import java.time.Instant

@Entity
@Table(name = "performances")
class Performance(
    concert: Concert,
    venue: String,
    startsAt: Instant,
    bookingOpensAt: Instant,
    bookingClosesAt: Instant,
) : BaseEntity() {
    @field:ManyToOne(fetch = FetchType.LAZY, optional = false)
    @field:JoinColumn(name = "concert_id", nullable = false)
    var concert: Concert = concert
        protected set

    @field:Column(nullable = false)
    var venue: String = venue
        protected set

    @field:Column(nullable = false)
    var startsAt: Instant = startsAt
        protected set

    @field:Column(nullable = false)
    var bookingOpensAt: Instant = bookingOpensAt
        protected set

    @field:Column(nullable = false)
    var bookingClosesAt: Instant = bookingClosesAt
        protected set

    init {
        require(venue.isNotBlank()) { "공연 장소는 비어 있을 수 없습니다." }
        require(bookingOpensAt < bookingClosesAt) { "예매 시작은 마감보다 빨라야 합니다." }
        require(bookingClosesAt <= startsAt) { "예매는 공연 시작까지 마감되어야 합니다." }
    }

    fun isBookingOpen(now: Instant) = now in bookingOpensAt..<bookingClosesAt
}
