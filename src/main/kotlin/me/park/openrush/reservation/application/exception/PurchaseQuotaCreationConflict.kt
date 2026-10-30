package me.park.openrush.reservation.application.exception

class PurchaseQuotaCreationConflict(cause: Throwable) : RuntimeException("이미 생성된 구매 한도가 있습니다.", cause)
