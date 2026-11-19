package me.park.openrush.reservation.application.exception

class PaymentAttemptConflict : RuntimeException("같은 결제 시도에는 같은 paymentKey를 사용해야 합니다.")
