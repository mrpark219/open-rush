package me.park.openrush.reservation.application.exception

class ReservationIdempotencyConflict : RuntimeException("이미 다른 예매 요청에 사용한 Idempotency-Key입니다.")
