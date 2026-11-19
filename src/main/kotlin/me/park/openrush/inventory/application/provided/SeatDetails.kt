package me.park.openrush.inventory.application.provided
import me.park.openrush.inventory.domain.SeatStatus

data class SeatDetails(val seatId: Long, val performanceId: Long, val amount: Long, val status: SeatStatus)
