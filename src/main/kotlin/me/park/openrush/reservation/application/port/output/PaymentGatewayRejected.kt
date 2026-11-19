package me.park.openrush.reservation.application.port.output

class PaymentGatewayRejected(cause: Throwable) : RuntimeException("PG가 결제 승인을 거절했습니다.", cause)
