package me.park.openrush.reservation.domain

import jakarta.persistence.*
import me.park.openrush.concert.domain.Performance
import java.time.Duration
import java.time.Instant
import java.util.*

@Entity
@Table(name = "reservations")
class Reservation private constructor(
    performance: Performance, userId: Long, heldAt: Instant, expiresAt: Instant,
) {
    @field:Id
    @field:GeneratedValue(strategy = GenerationType.IDENTITY)
    var id: Long? = null
        protected set

    @field:ManyToOne(fetch = FetchType.LAZY, optional = false)
    @field:JoinColumn(name = "performance_id", nullable = false)
    var performance: Performance = performance
        protected set

    @field:Column(nullable = false)
    var userId: Long = userId
        protected set

    @field:Column(nullable = false)
    var heldAt: Instant = heldAt
        protected set

    @field:Column(nullable = false)
    var expiresAt: Instant = expiresAt
        protected set

    @field:Enumerated(EnumType.STRING)
    @field:Column(nullable = false)
    var status: ReservationStatus = ReservationStatus.HELD
        protected set


    var confirmedAt: Instant? = null
        protected set

    @field:OneToMany(mappedBy = "reservation", cascade = [CascadeType.PERSIST, CascadeType.MERGE])
    private var reservationItems: MutableList<ReservationItem> = mutableListOf()

    @get:Transient
    val items: List<ReservationItem>
        get() = Collections.unmodifiableList(reservationItems)

    @get:Transient
    val quantity: Int get() = reservationItems.size

    @get:Transient
    val totalAmount: Money
        get() = Money(reservationItems.fold(0L) { sum, item -> Math.addExact(sum, item.price.amount) })

    fun confirm(userId: Long, now: Instant) {
        requireActive(now)
        checkRule(this.userId == userId, "예약자만 확정할 수 있습니다.")
        checkRule(now < expiresAt, "만료된 예매입니다.")
        status = ReservationStatus.CONFIRMED
        confirmedAt = now
    }

    fun cancel(userId: Long, now: Instant) {
        requireActive(now)
        checkRule(this.userId == userId, "예약자만 취소할 수 있습니다.")
        checkRule(now < expiresAt, "만료된 예매는 만료 처리해야 합니다.")
        status = ReservationStatus.CANCELLED
    }

    fun expire(now: Instant) {
        requireActive(now)
        checkRule(now >= expiresAt, "아직 만료되지 않은 예매입니다.")
        status = ReservationStatus.EXPIRED
    }

    private fun requireActive(now: Instant) {
        checkRule(status == ReservationStatus.HELD, "선점 상태에서만 전환할 수 있습니다.")
        checkRule(now >= heldAt, "선점 시각 이전에는 전환할 수 없습니다.")
    }

    companion object {
        /** 예매 기록을 생성한다. 좌석 선점·누적 한도 확보는 같은 트랜잭션에서 별도로 수행한다. */
        fun create(
            performance: Performance, userId: Long, seats: List<PerformanceSeat>,
            now: Instant, holdDuration: Duration = Duration.ofMinutes(5)
        ): Reservation {
            require(userId > 0) { "사용자 ID는 양수여야 합니다." }
            require(!holdDuration.isNegative && !holdDuration.isZero) { "선점 시간은 양수여야 합니다." }
            val performanceId = requireNotNull(performance.id) { "저장된 회차가 필요합니다." }
            checkRule(performance.isBookingOpen(now), "예매 가능한 시간이 아닙니다.")
            checkRule(
                seats.isNotEmpty() && seats.size <= performance.concert.maxTicketsPerUser,
                "한 예매에는 1~${performance.concert.maxTicketsPerUser}개 좌석이 필요합니다."
            )
            val seatIds = seats.map { requireNotNull(it.id) { "저장된 좌석이 필요합니다." } }
            checkRule(seatIds.distinct().size == seatIds.size, "중복 좌석은 예매할 수 없습니다.")
            checkRule(seats.all { it.performance.id == performanceId }, "같은 회차의 좌석만 예매할 수 있습니다.")
            checkRule(seats.all { it.status == SeatStatus.AVAILABLE }, "이미 점유된 좌석이 있습니다.")
            seats.fold(0L) { sum, seat -> Math.addExact(sum, seat.price.amount) }
            val reservation = Reservation(
                performance, userId, now,
                minOf(now.plus(holdDuration), performance.bookingClosesAt)
            )
            reservation.reservationItems.addAll(seats.map { ReservationItem(reservation, it) })
            return reservation
        }
    }
}
