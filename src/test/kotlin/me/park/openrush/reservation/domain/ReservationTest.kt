package me.park.openrush.reservation.domain

import me.park.openrush.reservation.domain.exception.ReservationRuleViolationException
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
            assertEquals(100_000L, reservation.totalAmount)
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
            assertFailsWith<ReservationRuleViolationException> {
                Reservation.create(performance, 1L, listOf(seat(1)), now.minusNanos(1))
            }
        }

        @Test
        fun `예매 마감 시각에는 생성할 수 없다`() {
            assertFailsWith<ReservationRuleViolationException> {
                Reservation.create(performance, 1L, listOf(seat(1)), performance.bookingClosesAt)
            }
        }
    }

    @Nested
    @DisplayName("좌석 조건")
    inner class SeatConditions {
        @Test
        fun `좌석을 선택하지 않으면 생성할 수 없다`() {
            assertFailsWith<ReservationRuleViolationException> {
                Reservation.create(performance, 1L, emptyList(), now)
            }
        }

        @Test
        fun `한도를 초과하면 생성할 수 없다`() {
            assertFailsWith<ReservationRuleViolationException> {
                Reservation.create(performance, 1L, listOf(seat(1), seat(2), seat(3)), now)
            }
        }

        @Test
        fun `같은 좌석을 중복으로 선택할 수 없다`() {
            val selectedSeat = seat(1)

            assertFailsWith<ReservationRuleViolationException> {
                Reservation.create(performance, 1L, listOf(selectedSeat, selectedSeat), now)
            }
        }

        @Test
        fun `다른 회차의 좌석은 선택할 수 없다`() {
            val otherSeat = seat(1, performance(2L))

            assertFailsWith<ReservationRuleViolationException> {
                Reservation.create(performance, 1L, listOf(otherSeat), now)
            }
        }

        @Test
        fun `이미 선점된 좌석은 선택할 수 없다`() {
            val selectedSeat = seat(1).copy(available = false)

            assertFailsWith<ReservationRuleViolationException> {
                Reservation.create(performance, 1L, listOf(selectedSeat), now)
            }
        }

        @Test
        fun `저장되지 않은 좌석은 선택할 수 없다`() {
            val unsavedSeat = ReservationSeat(null, requireNotNull(performance.performanceId), 50_000L, true)

            assertFailsWith<IllegalArgumentException> {
                Reservation.create(performance, 1L, listOf(unsavedSeat), now)
            }
        }
    }

    @Nested
    @DisplayName("입력값 검증")
    inner class InputValidation {
        @Test
        fun `음수 금액의 좌석은 사용할 수 없다`() {
            assertFailsWith<IllegalArgumentException> {
                ReservationSeat(1, requireNotNull(performance.performanceId), -1, true)
            }
        }

        @Test
        fun `좌석 금액 합산이 Long 범위를 넘으면 예약을 생성하지 않는다`() {
            assertFailsWith<ArithmeticException> {
                Reservation.create(
                    performance, 1L, listOf(
                        seat(1).copy(amount = Long.MAX_VALUE),
                        seat(2).copy(amount = 1),
                    ), now
                )
            }
        }

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
            val unsavedPerformance = ReservationBookingPolicy(null, now, now.plusSeconds(3600), 2)

            assertFailsWith<IllegalArgumentException> {
                Reservation.create(unsavedPerformance, 1L, listOf(seat(1)), now)
            }
        }
    }

    private fun performance(id: Long) = ReservationBookingPolicy(id, now, now.plusSeconds(3600), 2)

    private fun seat(id: Long, performance: ReservationBookingPolicy = this.performance) =
        ReservationSeat(id, requireNotNull(performance.performanceId), 50_000L, true)
}
