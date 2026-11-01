package me.park.openrush.reservation.support

import me.park.openrush.reservation.application.port.input.CreateReservationCommand
import me.park.openrush.reservation.application.port.input.CreateReservationResult
import me.park.openrush.reservation.application.port.input.CreateReservationUseCase
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.assertTrue

class ConcurrentReservationRequests(private val reservations: CreateReservationUseCase) {
    fun run(commands: List<CreateReservationCommand>): List<Result<CreateReservationResult>> {
        val executor = Executors.newFixedThreadPool(commands.size)
        val ready = CountDownLatch(commands.size)
        val start = CountDownLatch(1)
        try {
            val futures = commands.map { command ->
                executor.submit<Result<CreateReservationResult>> {
                    ready.countDown()
                    check(start.await(15, TimeUnit.SECONDS))
                    runCatching { reservations.create(command) }
                }
            }
            assertTrue(ready.await(15, TimeUnit.SECONDS))
            start.countDown()
            return futures.map { it.get(30, TimeUnit.SECONDS) }
        } finally {
            start.countDown()
            executor.shutdownNow()
            check(executor.awaitTermination(10, TimeUnit.SECONDS))
        }
    }

}
