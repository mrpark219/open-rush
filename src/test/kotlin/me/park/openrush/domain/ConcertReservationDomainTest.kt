package me.park.openrush.domain

import me.park.openrush.concert.domain.Concert
import me.park.openrush.concert.domain.Performance
import me.park.openrush.reservation.domain.*
import org.junit.jupiter.api.Test
import java.time.Duration
import java.time.Instant
import kotlin.test.*

class ConcertReservationDomainTest {
    private val now = Instant.parse("2026-11-01T09:00:00Z")
    private val concert = Concert("가을 콘서트", maxTicketsPerUser = 6)
    private val performance = performance(1L)

    private fun identify(entity: Any, id: Long) {
        entity.javaClass.getDeclaredField("id").apply { isAccessible = true }.set(entity, id)
    }

    private fun performance(id: Long, concert: Concert = this.concert) =
        Performance(concert, "서울", now.plusSeconds(7200), now, now.plusSeconds(3600))
            .also { identify(it, id) }

    private fun seats(count: Int = 2, performance: Performance = this.performance) = (1..count).map {
        PerformanceSeat(performance, SeatNumber("A-$it"), Money(50_000)).also { seat -> identify(seat, it.toLong()) }
    }

    private fun reservation() = Reservation.create(performance, 1L, seats(), now)

    @Test
    fun `여러 좌석을 묶고 가격과 수량을 기록한다`() {
        // given
        val selectedSeats = seats()

        // when
        val reservation = Reservation.create(performance, 1L, selectedSeats, now)

        // then
        assertNull(reservation.id)
        assertEquals(2, reservation.quantity)
        assertEquals(Money(100_000), reservation.totalAmount)
        assertEquals(now.plusSeconds(300), reservation.expiresAt)
        assertEquals(ReservationStatus.HELD, reservation.status)
    }

    @Test
    fun `예매 항목은 외부에서 삭제할 수 없다`() {
        // given
        val reservation = reservation()

        // when
        val result = runCatching { (reservation.items as MutableList).clear() }

        // then
        assertIs<UnsupportedOperationException>(result.exceptionOrNull())
        assertEquals(2, reservation.quantity)
    }

    @Test
    fun `빈 좌석 목록을 거절한다`() {
        // given
        val selectedSeats = emptyList<PerformanceSeat>()

        // when
        val result = runCatching { Reservation.create(performance, 1L, selectedSeats, now) }

        // then
        assertIs<ReservationRuleViolation>(result.exceptionOrNull())
    }

    @Test
    fun `중복 좌석을 거절한다`() {
        // given
        val seat = seats(1).single()

        // when
        val result = runCatching { Reservation.create(performance, 1L, listOf(seat, seat), now) }

        // then
        assertIs<ReservationRuleViolation>(result.exceptionOrNull())
    }

    @Test
    fun `다른 회차의 좌석을 거절한다`() {
        // given
        val selectedSeats = seats(1, performance(2L))

        // when
        val result = runCatching { Reservation.create(performance, 1L, selectedSeats, now) }

        // then
        assertIs<ReservationRuleViolation>(result.exceptionOrNull())
    }

    @Test
    fun `콘서트 설정 수량을 초과한 예매를 거절한다`() {
        // given
        val selectedSeats = seats(7)

        // when
        val result = runCatching { Reservation.create(performance, 1L, selectedSeats, now) }

        // then
        assertIs<ReservationRuleViolation>(result.exceptionOrNull())
    }

    @Test
    fun `예매 시작 전에는 예매할 수 없다`() {
        // given
        val requestTime = now.minusNanos(1)

        // when
        val result = runCatching { Reservation.create(performance, 1L, seats(), requestTime) }

        // then
        assertIs<ReservationRuleViolation>(result.exceptionOrNull())
    }

    @Test
    fun `예매 마감 시각에는 예매할 수 없다`() {
        // given
        val requestTime = performance.bookingClosesAt

        // when
        val result = runCatching { Reservation.create(performance, 1L, seats(), requestTime) }

        // then
        assertIs<ReservationRuleViolation>(result.exceptionOrNull())
    }

    @Test
    fun `콘서트 한도 6매까지 한 번에 예매할 수 있다`() {
        // given
        val selectedSeats = seats(6)

        // when
        val reservation = Reservation.create(performance, 1L, selectedSeats, now)

        // then
        assertEquals(6, reservation.quantity)
        assertEquals(Money(300_000), reservation.totalAmount)
    }

    @Test
    fun `한도가 1매인 콘서트는 2매 예매를 거절한다`() {
        // given
        val singlePerformance = performance(2L, Concert("1매 콘서트", maxTicketsPerUser = 1))

        // when
        val result = runCatching { Reservation.create(singlePerformance, 1L, seats(2, singlePerformance), now) }

        // then
        assertIs<ReservationRuleViolation>(result.exceptionOrNull())
    }

    @Test
    fun `양수가 아닌 사용자 ID를 거절한다`() {
        // given
        val userId = 0L

        // when
        val result = runCatching { Reservation.create(performance, userId, seats(), now) }

        // then
        assertIs<IllegalArgumentException>(result.exceptionOrNull())
    }

    @Test
    fun `0인 선점 기간을 거절한다`() {
        // given
        val duration = Duration.ZERO

        // when
        val result = runCatching { Reservation.create(performance, 1L, seats(), now, duration) }

        // then
        assertIs<IllegalArgumentException>(result.exceptionOrNull())
    }

    @Test
    fun `음수인 선점 기간을 거절한다`() {
        // given
        val duration = Duration.ofSeconds(-1)

        // when
        val result = runCatching { Reservation.create(performance, 1L, seats(), now, duration) }

        // then
        assertIs<IllegalArgumentException>(result.exceptionOrNull())
    }

    @Test
    fun `0인 구매 한도를 거절한다`() {
        // given
        val limit = 0

        // when
        val result = runCatching { Concert("콘서트", maxTicketsPerUser = limit) }

        // then
        assertIs<IllegalArgumentException>(result.exceptionOrNull())
    }

    @Test
    fun `음수인 구매 한도를 거절한다`() {
        // given
        val limit = -1

        // when
        val result = runCatching { Concert("콘서트", maxTicketsPerUser = limit) }

        // then
        assertIs<IllegalArgumentException>(result.exceptionOrNull())
    }

    @Test
    fun `음수 금액을 거절한다`() {
        // given
        val amount = -1L

        // when
        val result = runCatching { Money(amount) }

        // then
        assertIs<IllegalArgumentException>(result.exceptionOrNull())
    }

    @Test
    fun `빈 좌석 번호를 거절한다`() {
        // given
        val number = ""

        // when
        val result = runCatching { SeatNumber(number) }

        // then
        assertIs<IllegalArgumentException>(result.exceptionOrNull())
    }

    @Test
    fun `빈 공연 제목을 거절한다`() {
        // given
        val title = " "

        // when
        val result = runCatching { Concert(title, maxTicketsPerUser = 2) }

        // then
        assertIs<IllegalArgumentException>(result.exceptionOrNull())
    }

    @Test
    fun `저장되지 않은 좌석을 거절한다`() {
        // given
        val seat = PerformanceSeat(performance, SeatNumber("B-1"), Money(1))

        // when
        val result = runCatching { Reservation.create(performance, 1L, listOf(seat), now) }

        // then
        assertIs<IllegalArgumentException>(result.exceptionOrNull())
    }

    @Test
    fun `선점 기한은 예매 마감을 넘지 않는다`() {
        // given
        val requestTime = performance.bookingClosesAt.minusSeconds(1)

        // when
        val reservation = Reservation.create(performance, 1L, seats(), requestTime)

        // then
        assertEquals(performance.bookingClosesAt, reservation.expiresAt)
    }

    @Test
    fun `만료 직전에는 예매를 확정할 수 있다`() {
        // given
        val reservation = reservation()
        val confirmedAt = reservation.expiresAt.minusNanos(1)

        // when
        reservation.confirm(1L, confirmedAt)

        // then
        assertEquals(ReservationStatus.CONFIRMED, reservation.status)
        assertEquals(confirmedAt, reservation.confirmedAt)
    }

    @Test
    fun `다른 사용자의 확정을 거절한다`() {
        // given
        val reservation = reservation()

        // when
        val result = runCatching { reservation.confirm(2L, now) }

        // then
        assertIs<ReservationRuleViolation>(result.exceptionOrNull())
        assertEquals(ReservationStatus.HELD, reservation.status)
    }

    @Test
    fun `선점 이전 시각의 확정을 거절한다`() {
        // given
        val reservation = reservation()

        // when
        val result = runCatching { reservation.confirm(1L, now.minusNanos(1)) }

        // then
        assertIs<ReservationRuleViolation>(result.exceptionOrNull())
        assertEquals(ReservationStatus.HELD, reservation.status)
    }

    @Test
    fun `확정된 예매의 만료를 거절한다`() {
        // given
        val reservation = reservation()
        reservation.confirm(1L, now)

        // when
        val result = runCatching { reservation.expire(reservation.expiresAt) }

        // then
        assertIs<ReservationRuleViolation>(result.exceptionOrNull())
        assertEquals(ReservationStatus.CONFIRMED, reservation.status)
    }

    @Test
    fun `확정된 예매의 취소를 거절한다`() {
        // given
        val reservation = reservation()
        reservation.confirm(1L, now)

        // when
        val result = runCatching { reservation.cancel(1L, now) }

        // then
        assertIs<ReservationRuleViolation>(result.exceptionOrNull())
        assertEquals(ReservationStatus.CONFIRMED, reservation.status)
    }

    @Test
    fun `만료 이전의 만료 처리를 거절한다`() {
        // given
        val reservation = reservation()

        // when
        val result = runCatching { reservation.expire(reservation.expiresAt.minusNanos(1)) }

        // then
        assertIs<ReservationRuleViolation>(result.exceptionOrNull())
        assertEquals(ReservationStatus.HELD, reservation.status)
    }

    @Test
    fun `만료 시각의 확정을 거절한다`() {
        // given
        val reservation = reservation()

        // when
        val result = runCatching { reservation.confirm(1L, reservation.expiresAt) }

        // then
        assertIs<ReservationRuleViolation>(result.exceptionOrNull())
        assertEquals(ReservationStatus.HELD, reservation.status)
    }

    @Test
    fun `만료 시각의 취소를 거절한다`() {
        // given
        val reservation = reservation()

        // when
        val result = runCatching { reservation.cancel(1L, reservation.expiresAt) }

        // then
        assertIs<ReservationRuleViolation>(result.exceptionOrNull())
        assertEquals(ReservationStatus.HELD, reservation.status)
    }

    @Test
    fun `만료 처리는 한 번만 허용한다`() {
        // given
        val reservation = reservation()
        reservation.expire(reservation.expiresAt)

        // when
        val result = runCatching { reservation.expire(reservation.expiresAt) }

        // then
        assertIs<ReservationRuleViolation>(result.exceptionOrNull())
        assertEquals(ReservationStatus.EXPIRED, reservation.status)
    }

    @Test
    fun `다른 사용자의 취소를 거절한다`() {
        // given
        val reservation = reservation()

        // when
        val result = runCatching { reservation.cancel(2L, now) }

        // then
        assertIs<ReservationRuleViolation>(result.exceptionOrNull())
        assertEquals(ReservationStatus.HELD, reservation.status)
    }

    @Test
    fun `취소 처리는 한 번만 허용한다`() {
        // given
        val reservation = reservation()
        reservation.cancel(1L, now)

        // when
        val result = runCatching { reservation.cancel(1L, now) }

        // then
        assertIs<ReservationRuleViolation>(result.exceptionOrNull())
        assertEquals(ReservationStatus.CANCELLED, reservation.status)
    }

    @Test
    fun `점유된 좌석의 중복 선점을 거절한다`() {
        // given
        val seat = seats(1).single()
        seat.hold(1L)

        // when
        val result = runCatching { seat.hold(2L) }

        // then
        assertIs<ReservationRuleViolation>(result.exceptionOrNull())
        assertEquals(SeatStatus.HELD, seat.status)
    }

    @Test
    fun `다른 예매의 좌석 확정을 거절한다`() {
        // given
        val seat = seats(1).single()
        seat.hold(1L)

        // when
        val result = runCatching { seat.confirm(2L) }

        // then
        assertIs<ReservationRuleViolation>(result.exceptionOrNull())
        assertEquals(SeatStatus.HELD, seat.status)
    }

    @Test
    fun `점유된 좌석으로 예매를 생성할 수 없다`() {
        // given
        val seat = seats(1).single()
        seat.hold(1L)

        // when
        val result = runCatching { Reservation.create(performance, 1L, listOf(seat), now) }

        // then
        assertIs<ReservationRuleViolation>(result.exceptionOrNull())
        assertEquals(SeatStatus.HELD, seat.status)
    }

    @Test
    fun `이전 예매가 재선점된 좌석을 반환할 수 없다`() {
        // given
        val seat = seats(1).single()
        seat.hold(1L)
        seat.release(1L)
        seat.hold(2L)

        // when
        val result = runCatching { seat.release(1L) }

        // then
        assertIs<ReservationRuleViolation>(result.exceptionOrNull())
        assertEquals(SeatStatus.HELD, seat.status)
    }

    @Test
    fun `확정된 좌석을 선점 해제로 반환할 수 없다`() {
        // given
        val seat = seats(1).single()
        seat.hold(1L)
        seat.confirm(1L)

        // when
        val result = runCatching { seat.release(1L) }

        // then
        assertIs<ReservationRuleViolation>(result.exceptionOrNull())
        assertEquals(SeatStatus.RESERVED, seat.status)
    }

    @Test
    fun `같은 콘서트의 다른 회차 수량을 합산한다`() {
        // given
        val quota = ConcertPurchaseQuota(1L, concert)
        val first = Reservation.create(performance, 1L, seats(2), now)
        val secondPerformance = performance(2L)
        val second = Reservation.create(secondPerformance, 1L, seats(4, secondPerformance), now)

        // when
        quota.allocate(first.quantity)
        quota.allocate(second.quantity)
        first.confirm(1L, now)

        // then
        assertEquals(6, quota.allocatedQuantity)
    }

    @Test
    fun `콘서트마다 서로 다른 구매 한도를 적용한다`() {
        // given
        val small = ConcertPurchaseQuota(1L, Concert("소규모 공연", maxTicketsPerUser = 2))
        val large = ConcertPurchaseQuota(1L, Concert("대규모 공연", maxTicketsPerUser = 8))

        // when
        small.allocate(2)
        large.allocate(8)

        // then
        assertEquals(2, small.allocatedQuantity)
        assertEquals(8, large.allocatedQuantity)
    }

    @Test
    fun `취소하면 확보한 수량을 반환한다`() {
        // given
        val quota = ConcertPurchaseQuota(1L, concert)
        val reservation = reservation()
        quota.allocate(reservation.quantity)

        // when
        reservation.cancel(1L, now)
        quota.release(reservation.quantity)

        // then
        assertEquals(0, quota.allocatedQuantity)
        assertEquals(ReservationStatus.CANCELLED, reservation.status)
    }

    @Test
    fun `설정 한도를 초과해 추가 확보할 수 없다`() {
        // given
        val quota = ConcertPurchaseQuota(1L, concert)
        quota.allocate(6)

        // when
        val result = runCatching { quota.allocate(1) }

        // then
        assertIs<ReservationRuleViolation>(result.exceptionOrNull())
        assertEquals(6, quota.allocatedQuantity)
    }

    @Test
    fun `0매를 확보할 수 없다`() {
        // given
        val quota = ConcertPurchaseQuota(1L, concert)

        // when
        val result = runCatching { quota.allocate(0) }

        // then
        assertIs<IllegalArgumentException>(result.exceptionOrNull())
        assertEquals(0, quota.allocatedQuantity)
    }

    @Test
    fun `음수 수량을 반환할 수 없다`() {
        // given
        val quota = ConcertPurchaseQuota(1L, concert)

        // when
        val result = runCatching { quota.release(-1) }

        // then
        assertIs<IllegalArgumentException>(result.exceptionOrNull())
        assertEquals(0, quota.allocatedQuantity)
    }

    @Test
    fun `확보량보다 많이 반환할 수 없다`() {
        // given
        val quota = ConcertPurchaseQuota(1L, concert)
        quota.allocate(2)

        // when
        val result = runCatching { quota.release(3) }

        // then
        assertIs<ReservationRuleViolation>(result.exceptionOrNull())
        assertEquals(2, quota.allocatedQuantity)
    }

    @Test
    fun `매우 큰 수량도 한도 검증으로 거절한다`() {
        // given
        val quota = ConcertPurchaseQuota(1L, concert)
        quota.allocate(2)

        // when
        val result = runCatching { quota.allocate(Int.MAX_VALUE) }

        // then
        assertIs<ReservationRuleViolation>(result.exceptionOrNull())
        assertEquals(2, quota.allocatedQuantity)
    }

    @Test
    fun `예약 금액 합계의 오버플로를 거절한다`() {
        // given
        val expensive = (1..2).map {
            PerformanceSeat(performance, SeatNumber("B-$it"), Money(Long.MAX_VALUE))
                .also { seat -> identify(seat, it.toLong()) }
        }

        // when
        val result = runCatching { Reservation.create(performance, 1L, expensive, now) }

        // then
        assertIs<ArithmeticException>(result.exceptionOrNull())
    }
}
