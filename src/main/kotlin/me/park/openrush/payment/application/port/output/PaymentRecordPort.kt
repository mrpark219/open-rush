package me.park.openrush.payment.application.port.output

import me.park.openrush.payment.domain.PaymentRecord
import me.park.openrush.payment.domain.VerifiedPayment

interface PaymentRecordPort {
    /** 같은 트랜잭션에서 결과를 저장할 결제 키를 한 번만 확보한다. */
    fun claim(payment: VerifiedPayment): Boolean

    fun find(provider: String, paymentId: String): PaymentRecord?

    fun complete(record: PaymentRecord)
}
