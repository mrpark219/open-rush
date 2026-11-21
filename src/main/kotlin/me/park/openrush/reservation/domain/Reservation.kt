package me.park.openrush.reservation.domain

import jakarta.persistence.*
import me.park.openrush.reservation.domain.exception.checkRule
import me.park.openrush.support.persistence.BaseEntity
import java.time.Duration
import java.time.Instant
import java.util.*

@Entity
@Table(name = "reservations")
class Reservation private constructor(
    performanceId: Long,
    userId: Long,
    heldAt: Instant,
    expiresAt: Instant,
) : BaseEntity() {
    @field:Column(name = "performance_id", nullable = false)
    var performanceId: Long = performanceId
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
    val totalAmount: Long
        get() = reservationItems.fold(0L) { sum, item -> Math.addExact(sum, item.amount) }

    fun confirm(userId: Long, now: Instant) {
        validateConfirmation(userId, now)
        status = ReservationStatus.CONFIRMED
        confirmedAt = now
    }

    fun validateConfirmation(userId: Long, now: Instant) {
        requireActive(now)
        checkRule(this.userId == userId, "예약자만 확정할 수 있습니다.")
        checkRule(now < expiresAt, "만료된 예매입니다.")
    }

    fun cancelHold(userId: Long, now: Instant) {
        validateHoldCancellation(userId, now)
        status = ReservationStatus.CANCELLED
    }

    /** 상태를 바꾸지 않고 취소 정책을 검증한다. 동시 요청은 저장 시 조건부 전환으로 검사한다. */
    fun validateHoldCancellation(userId: Long, now: Instant) {
        requireActive(now)
        checkRule(this.userId == userId, "예약자만 취소할 수 있습니다.")
        checkRule(now < expiresAt, "만료된 예매는 만료 처리해야 합니다.")
    }

    fun cancelConfirmed(userId: Long) {
        checkRule(this.userId == userId, "예약자만 취소할 수 있습니다.")
        checkRule(status == ReservationStatus.CONFIRMED, "확정된 예약만 취소할 수 있습니다.")
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
        fun create(
            bookingPolicy: ReservationBookingPolicy,
            userId: Long,
            seats: List<ReservationSeat>,
            now: Instant,
            holdDuration: Duration = Duration.ofMinutes(5),
        ): Reservation {
            require(userId > 0) { "사용자 ID는 양수여야 합니다." }
            require(!holdDuration.isNegative && !holdDuration.isZero) { "선점 시간은 양수여야 합니다." }

            val performanceId = requireNotNull(bookingPolicy.performanceId) { "저장된 회차가 필요합니다." }
            checkRule(bookingPolicy.isBookingOpen(now), "예매 가능한 시간이 아닙니다.")

            checkRule(
                seats.isNotEmpty() && seats.size <= bookingPolicy.maxTicketsPerUser,
                "한 예매에는 1~${bookingPolicy.maxTicketsPerUser}개 좌석이 필요합니다."
            )

            val seatIds = seats.map { seat ->
                requireNotNull(seat.id) { "저장된 좌석이 필요합니다." }
            }
            checkRule(seatIds.distinct().size == seatIds.size, "중복 좌석은 예매할 수 없습니다.")
            checkRule(seats.all { seat -> seat.performanceId == performanceId }, "같은 회차의 좌석만 예매할 수 있습니다.")
            checkRule(seats.all { seat -> seat.available }, "이미 점유된 좌석이 있습니다.")

            seats.fold(0L) { sum, seat -> Math.addExact(sum, seat.amount) }

            val reservation = Reservation(
                performanceId,
                userId,
                now,
                minOf(now.plus(holdDuration), bookingPolicy.bookingClosesAt),
            )

            reservation.reservationItems.addAll(
                seats.map { seat -> ReservationItem(reservation, seat) }
            )

            return reservation
        }
    }
}
