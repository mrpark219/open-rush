package me.park.openrush.reservation.application.required

import me.park.openrush.reservation.application.provided.CreateReservationResult

data class ReservationCreationRecord(val request: String, val result: CreateReservationResult)
