package me.park.openrush.reservation.application.exception

class PaymentRecordConflictException(cause: Throwable? = null) :
    IllegalArgumentException("기록된 결제와 재전달된 결제 정보가 다릅니다.", cause)
