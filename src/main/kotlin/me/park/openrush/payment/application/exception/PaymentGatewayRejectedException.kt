package me.park.openrush.payment.application.exception

class PaymentGatewayRejectedException(cause: Throwable) : RuntimeException("PG가 결제 승인을 거절했습니다.", cause)
