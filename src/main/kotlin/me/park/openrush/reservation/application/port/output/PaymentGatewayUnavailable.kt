package me.park.openrush.reservation.application.port.output

class PaymentGatewayUnavailable(cause: Throwable? = null) : RuntimeException("PG 승인 여부를 확인할 수 없습니다.", cause)
