package me.park.openrush.domain

import me.park.openrush.concert.domain.Concert
import me.park.openrush.concert.domain.Performance
import me.park.openrush.reservation.domain.*
import org.hibernate.cfg.Configuration
import org.junit.jupiter.api.Test
import java.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class DomainJpaMappingTest {
    @Test
    fun `설정된 한도와 다중 좌석 예매 및 종료 이력을 저장한다`() {
        // given: 1인 한도가 6매인 콘서트의 두 회차에 각각 3매를 선점한다.
        val config = Configuration()
        listOf(
            Concert::class.java, Performance::class.java, PerformanceSeat::class.java,
            Reservation::class.java, ReservationItem::class.java, ConcertPurchaseQuota::class.java
        ).forEach { config.addAnnotatedClass(it) }
        config.setProperty("hibernate.connection.driver_class", "org.h2.Driver")
            .setProperty("hibernate.connection.url", "jdbc:h2:mem:domain;DB_CLOSE_DELAY=-1")
            .setProperty("hibernate.hbm2ddl.auto", "create-drop")

        config.buildSessionFactory().use { factory ->
            val now = Instant.parse("2026-11-01T09:00:00Z")
            var reservationId = 0L
            var quotaId = 0L
            var secondPerformanceId = 0L
            var nextReservationId = 0L
            factory.openSession().use { session ->
                val tx = session.beginTransaction()
                val concert = Concert("가을 콘서트", maxTicketsPerUser = 6)
                session.persist(concert)
                val quota = ConcertPurchaseQuota(1L, concert)
                session.persist(quota)
                quotaId = requireNotNull(quota.id)
                repeat(2) { index ->
                    val performance = Performance(concert, "서울", now.plusSeconds(7200), now, now.plusSeconds(3600))
                    session.persist(performance)
                    val seats = (1..3).map {
                        PerformanceSeat(performance, SeatNumber("A-$it"), Money(50_000)).also(session::persist)
                    }
                    val reservation = Reservation.create(performance, 1L, seats, now)
                    quota.allocate(reservation.quantity)
                    session.persist(reservation)
                    val id = requireNotNull(reservation.id)
                    seats.forEach { it.hold(id) }
                    if (index == 0) reservationId = id else secondPerformanceId = requireNotNull(performance.id)
                }
                tx.commit()
            }

            // when: 첫 예매를 취소한 뒤 반환된 좌석과 한도로 다시 예매하여 확정한다.
            factory.openSession().use { session ->
                val tx = session.beginTransaction()
                val quota = session.find(ConcertPurchaseQuota::class.java, quotaId)
                val reservation = session.find(Reservation::class.java, reservationId)
                reservation.cancel(1L, now.plusSeconds(1))
                reservation.items.forEach { it.seat.release(reservationId) }
                quota.release(reservation.quantity)
                tx.commit()
            }
            factory.openSession().use { session ->
                val tx = session.beginTransaction()
                val quota = session.find(ConcertPurchaseQuota::class.java, quotaId)
                val old = session.find(Reservation::class.java, reservationId)
                val seats = old.items.map { it.seat }
                val next = Reservation.create(old.performance, 1L, seats, now.plusSeconds(2))
                quota.allocate(next.quantity)
                session.persist(next)
                nextReservationId = requireNotNull(next.id)
                seats.forEach { it.hold(nextReservationId) }
                next.confirm(1L, now.plusSeconds(3))
                seats.forEach { it.confirm(nextReservationId) }
                tx.commit()
            }

            // then: 설정 한도와 확정 상태가 재조회되며 이전 취소 예매의 이력도 남는다.
            factory.openSession().use { session ->
                val quota = session.find(ConcertPurchaseQuota::class.java, quotaId)
                assertEquals(6, quota.concert.maxTicketsPerUser)
                assertEquals(6, quota.allocatedQuantity)
                assertFailsWith<ReservationRuleViolation> { quota.allocate(1) }

                val old = session.find(Reservation::class.java, reservationId)
                assertEquals(ReservationStatus.CANCELLED, old.status)
                assertEquals(3, old.items.size)
                assertTrue(old.items.all { it.id != null && it.seat.reservationId == nextReservationId })
                assertTrue(old.items.all { it.seat.status == SeatStatus.RESERVED })

                val next = session.find(Reservation::class.java, nextReservationId)
                assertNotNull(next.id)
                assertEquals(ReservationStatus.CONFIRMED, next.status)
                assertEquals(now.plusSeconds(3), next.confirmedAt)
                assertEquals(Money(150_000), next.totalAmount)
                val second = session.find(Performance::class.java, secondPerformanceId)
                assertEquals(old.performance.concert.id, second.concert.id)
            }
        }
    }
}
