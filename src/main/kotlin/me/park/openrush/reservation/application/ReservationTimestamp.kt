package me.park.openrush.reservation.application

import java.time.Clock
import java.time.Instant
import java.time.temporal.ChronoUnit

// TIMESTAMP(6) 정밀도에 맞춰 만료 직전 시각이 반올림되어 기한에 닿지 않게 한다.
internal fun Clock.reservationTimestamp(): Instant = instant().truncatedTo(ChronoUnit.MICROS)
