package me.park.openrush.reservation

import me.park.openrush.common.exception.ConcurrencyException
import me.park.openrush.reservation.application.port.input.CreateReservationCommand
import me.park.openrush.reservation.application.port.input.CreateReservationResult
import me.park.openrush.reservation.application.port.output.AllocateQuotaCommand
import me.park.openrush.reservation.application.port.output.HoldSeatsCommand
import me.park.openrush.reservation.domain.Reservation
import me.park.openrush.reservation.domain.exception.ReservationRuleViolation
import me.park.openrush.reservation.support.ReservationConcurrencyTestSupport
import org.junit.jupiter.api.Test
import org.springframework.dao.OptimisticLockingFailureException
import org.springframework.test.context.TestPropertySource
import org.springframework.transaction.support.TransactionTemplate
import java.time.Instant
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.ExecutionException
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.test.fail

@TestPropertySource(properties = ["reservation.concurrency-strategy=optimistic"])
class OptimisticReservationConcurrencyIntegrationTest : ReservationConcurrencyTestSupport() {
    @Test
    fun `동일 좌석에 동시 요청하면 한 예매만 성공한다`() {
        val results = race((1L..24L).map { command(it, seatIds.take(1)) })

        assertOptimisticOutcomes(results, 1..1)
        assertEquals(1, count("select version from performance_seats where id = ${seatIds.first()}"))
        assertConsistent(1, 1)
    }

    @Test
    fun `최초 구매 한도 생성 경쟁에서도 한도를 초과하지 않는다`() {
        val results = race(seatIds.map { command(1, listOf(it)) })
        val successes = assertOptimisticOutcomes(results, 1..4)

        assertEquals(1, count("select count(*) from concert_purchase_quotas"))
        assertConsistent(successes, successes)
    }

    @Test
    fun `기존 구매 한도를 두 회차에서 동시에 사용해도 한도를 초과하지 않는다`() {
        createEmptyQuota()
        val commands = seatIds.take(12).map { command(1, listOf(it)) } +
            otherSeatIds.take(12).map { CreateReservationCommand(1, otherPerformanceId, listOf(it)) }

        val successes = assertOptimisticOutcomes(race(commands), 1..4)

        assertEquals(successes, count("select version from concert_purchase_quotas where user_id = 1"))
        assertConsistent(successes, successes)
    }

    @Test
    fun `역순 다중 좌석 요청도 한 예매만 성공한다`() {
        val commands = (1L..24L).map {
            command(it, if (it % 2L == 0L) seatIds.take(2) else seatIds.take(2).reversed())
        }

        assertOptimisticOutcomes(race(commands), 1..1)
        assertConsistent(1, 2)
    }

    @Test
    fun `좌석 버전 충돌은 앞선 좌석 변경과 예매 구매 한도를 모두 롤백한다`() {
        val saved = CountDownLatch(1)
        val winnerCommitted = CountDownLatch(1)
        val executor = Executors.newSingleThreadExecutor()

        try {
            val loser = executor.submit<Throwable?> {
                runCatching {
                    TransactionTemplate(transactionManager).executeWithoutResult {
                        val performance = checkNotNull(performancePort.findPerformance(performanceId))
                        val selectedSeats = seatsPort.findSeats(performanceId, seatIds.take(2))
                        val reservation = Reservation.create(performance, 2, selectedSeats, Instant.now())

                        quotaPort.allocate(AllocateQuotaCommand.from(reservation))
                        val savedReservation = saveReservationPort.save(reservation)
                        saved.countDown()
                        check(winnerCommitted.await(15, TimeUnit.SECONDS))

                        holdSeatsPort.hold(HoldSeatsCommand.from(savedReservation))
                    }
                }.exceptionOrNull()
            }

            assertTrue(saved.await(15, TimeUnit.SECONDS))
            reservations.create(command(1, listOf(seatIds[1])))
            winnerCommitted.countDown()

            assertIs<OptimisticLockingFailureException>(loser.get(20, TimeUnit.SECONDS))
            assertEquals("AVAILABLE", jdbc.queryForObject("select status from performance_seats where id = ?", String::class.java, seatIds[0]))
            assertEquals(0, count("select version from performance_seats where id = ${seatIds[0]}"))
            assertEquals(0, count("select count(*) from concert_purchase_quotas where user_id = 2"))
            assertConsistent(1, 1)
        } finally {
            winnerCommitted.countDown()
            executor.shutdownNow()
            check(executor.awaitTermination(15, TimeUnit.SECONDS))
        }
    }

    @Test
    fun `구매 한도 버전 충돌은 재시도 없이 실패하고 다른 좌석은 남는다`() {
        createEmptyQuota()
        val executor = Executors.newSingleThreadExecutor()

        try {
            val loser = TransactionTemplate(transactionManager).execute {
                val performance = checkNotNull(performancePort.findPerformance(performanceId))
                val selectedSeats = seatsPort.findSeats(performanceId, seatIds.take(1))
                val winner = Reservation.create(performance, 1, selectedSeats, Instant.now())

                quotaPort.allocate(AllocateQuotaCommand.from(winner))
                val savedWinner = saveReservationPort.save(winner)
                holdSeatsPort.hold(HoldSeatsCommand.from(savedWinner))

                val pending = executor.submit<CreateReservationResult> {
                    reservations.create(command(1, listOf(seatIds[1])))
                }
                awaitQuotaUpdateWaitingForCommit()

                pending
            }

            val failure = assertFailsWith<ExecutionException> { checkNotNull(loser).get(15, TimeUnit.SECONDS) }
            assertIs<ConcurrencyException>(failure.cause)
            assertEquals("AVAILABLE", jdbc.queryForObject("select status from performance_seats where id = ?", String::class.java, seatIds[1]))
            assertEquals(1, count("select version from concert_purchase_quotas where user_id = 1"))
            assertConsistent(1, 1)
        } finally {
            executor.shutdownNow()
            check(executor.awaitTermination(15, TimeUnit.SECONDS))
        }
    }

    private fun createEmptyQuota() {
        val concertId = id("select concert_id from performances where id = $performanceId")
        jdbc.update("insert into concert_purchase_quotas (user_id, concert_id, allocated_quantity) values (1, ?, 0)", concertId)
    }

    private fun assertOptimisticOutcomes(
        results: List<Result<CreateReservationResult>>,
        expectedSuccesses: IntRange,
    ): Int {
        val successes = results.count { it.isSuccess }
        assertTrue(successes in expectedSuccesses, "성공 수 $successes, 허용 범위 $expectedSuccesses")

        results.filter { it.isFailure }.forEach {
            val failure = it.exceptionOrNull()
            assertTrue(failure is ReservationRuleViolation || failure is ConcurrencyException, "예상하지 못한 실패: $failure")
        }

        return successes
    }

    private fun awaitQuotaUpdateWaitingForCommit() {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(4)

        while (System.nanoTime() < deadline) {
            jdbc.execute("select pg_stat_clear_snapshot()")
            val waiting = count("""
                select count(*) from pg_stat_activity
                where datname = current_database() and wait_event_type = 'Lock'
                  and query ilike '%update%concert_purchase_quotas%'
            """.trimIndent())

            if (waiting > 0) return

            Thread.sleep(10)
        }

        fail("구매 한도 UPDATE가 다른 트랜잭션의 커밋을 기다려야 합니다.")
    }
}
