package me.park.openrush.domain

import me.park.openrush.common.domain.BaseEntity
import me.park.openrush.common.domain.value.Money
import me.park.openrush.concert.domain.Concert
import me.park.openrush.concert.domain.Performance
import me.park.openrush.reservation.domain.Reservation
import me.park.openrush.reservation.domain.ReservationStatus
import me.park.openrush.reservation.domain.exception.ReservationRuleViolation
import me.park.openrush.reservation.domain.seat.PerformanceSeat
import me.park.openrush.reservation.domain.seat.SeatNumber
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import java.time.Duration
import java.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class ReservationTest {
    private val now = Instant.parse("2026-11-01T09:00:00Z")
    private val concert = Concert("가을 콘서트", maxTicketsPerUser = 2)
    private val performance = performance(1L)

    @Nested
    @DisplayName("예매 생성")
    inner class Creation {
        @Test
        fun `선택한 좌석으로 선점 예매를 생성한다`() {
            val reservation = Reservation.create(performance, 1L, listOf(seat(1), seat(2)), now)

            assertNull(reservation.id)
            assertEquals(ReservationStatus.HELD, reservation.status)
            assertEquals(2, reservation.quantity)
            assertEquals(Money(100_000), reservation.totalAmount)
            assertEquals(now, reservation.heldAt)
            assertEquals(now.plusSeconds(300), reservation.expiresAt)
            assertNull(reservation.confirmedAt)
        }

        @Test
        fun `선점 만료 시각은 예매 마감을 넘지 않는다`() {
            val requestTime = performance.bookingClosesAt.minusSeconds(1)
            val reservation = Reservation.create(performance, 1L, listOf(seat(1)), requestTime)

            assertEquals(performance.bookingClosesAt, reservation.expiresAt)
        }
    }

    @Nested
    @DisplayName("예매 시간")
    inner class BookingTime {
        @Test
        fun `예매 시작 전에는 생성할 수 없다`() {
            assertFailsWith<ReservationRuleViolation> {
                Reservation.create(performance, 1L, listOf(seat(1)), now.minusNanos(1))
            }
        }

        @Test
        fun `예매 마감 시각에는 생성할 수 없다`() {
            assertFailsWith<ReservationRuleViolation> {
                Reservation.create(performance, 1L, listOf(seat(1)), performance.bookingClosesAt)
            }
        }
    }

    @Nested
    @DisplayName("좌석 조건")
    inner class SeatConditions {
        @Test
        fun `좌석을 선택하지 않으면 생성할 수 없다`() {
            assertFailsWith<ReservationRuleViolation> {
                Reservation.create(performance, 1L, emptyList(), now)
            }
        }

        @Test
        fun `한도를 초과하면 생성할 수 없다`() {
            assertFailsWith<ReservationRuleViolation> {
                Reservation.create(performance, 1L, listOf(seat(1), seat(2), seat(3)), now)
            }
        }

        @Test
        fun `같은 좌석을 중복으로 선택할 수 없다`() {
            val selectedSeat = seat(1)

            assertFailsWith<ReservationRuleViolation> {
                Reservation.create(performance, 1L, listOf(selectedSeat, selectedSeat), now)
            }
        }

        @Test
        fun `다른 회차의 좌석은 선택할 수 없다`() {
            val otherSeat = seat(1, performance(2L))

            assertFailsWith<ReservationRuleViolation> {
                Reservation.create(performance, 1L, listOf(otherSeat), now)
            }
        }

        @Test
        fun `이미 선점된 좌석은 선택할 수 없다`() {
            val selectedSeat = seat(1).also { seat -> seat.hold(99L) }

            assertFailsWith<ReservationRuleViolation> {
                Reservation.create(performance, 1L, listOf(selectedSeat), now)
            }
        }

        @Test
        fun `저장되지 않은 좌석은 선택할 수 없다`() {
            val unsavedSeat = PerformanceSeat(performance, SeatNumber("A-1"), Money(50_000))

            assertFailsWith<IllegalArgumentException> {
                Reservation.create(performance, 1L, listOf(unsavedSeat), now)
            }
        }
    }

    @Nested
    @DisplayName("입력값 검증")
    inner class InputValidation {
        @Test
        fun `사용자 ID는 양수여야 한다`() {
            assertFailsWith<IllegalArgumentException> {
                Reservation.create(performance, 0L, listOf(seat(1)), now)
            }
        }

        @Test
        fun `선점 기간은 양수여야 한다`() {
            listOf(Duration.ZERO, Duration.ofSeconds(-1)).forEach { duration ->
                assertFailsWith<IllegalArgumentException> {
                    Reservation.create(performance, 1L, listOf(seat(1)), now, duration)
                }
            }
        }

        @Test
        fun `저장되지 않은 회차로 예매할 수 없다`() {
            val unsavedPerformance = Performance(concert, "서울", now.plusSeconds(7200), now, now.plusSeconds(3600))

            assertFailsWith<IllegalArgumentException> {
                Reservation.create(unsavedPerformance, 1L, listOf(seat(1)), now)
            }
        }
    }

    private fun identify(entity: BaseEntity, id: Long) {
        BaseEntity::class.java.getDeclaredField("id").apply { isAccessible = true }.set(entity, id)
    }

    private fun performance(id: Long): Performance =
        Performance(concert, "서울", now.plusSeconds(7200), now, now.plusSeconds(3600))
            .also { performance -> identify(performance, id) }

    private fun seat(id: Long, performance: Performance = this.performance): PerformanceSeat =
        PerformanceSeat(performance, SeatNumber("A-$id"), Money(50_000))
            .also { seat -> identify(seat, id) }
}
