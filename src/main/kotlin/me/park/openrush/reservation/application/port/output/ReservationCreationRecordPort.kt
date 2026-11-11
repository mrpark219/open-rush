package me.park.openrush.reservation.application.port.output

import me.park.openrush.reservation.application.port.input.CreateReservationResult

data class ReservationCreationRecord(val request: String, val result: CreateReservationResult)

interface ReservationCreationRecordPort {
    fun find(userId: Long, key: String): ReservationCreationRecord?

    /** 같은 트랜잭션에서 완료할 키를 확보한다. */
    fun claim(userId: Long, key: String, request: String): Boolean

    fun complete(userId: Long, key: String, result: CreateReservationResult)
}
