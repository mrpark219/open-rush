package me.park.openrush.reservation.domain.payment

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Table
import org.hibernate.annotations.CreationTimestamp
import java.time.Instant
import java.util.UUID

@Entity
@Table(name = "reservation_payment_tx")
class ReservationPaymentTx private constructor(
    @field:Column(nullable = false)
    val reservationId: Long,
    @field:Enumerated(EnumType.STRING)
    @field:Column(nullable = false, length = 32)
    val type: ReservationPaymentTxType,
    @field:Column(nullable = false, length = 32)
    val provider: String,
    @field:Column(nullable = false)
    val amount: Long,
    val attemptId: UUID? = null,
    @field:Column(length = 64)
    val pgOrderId: String? = null,
    @field:Column(length = 128)
    val approvalIdempotencyKey: String? = null,
    @field:Column(length = 255)
    val paymentId: String? = null,
    @field:Column(length = 255)
    val cancellationId: String? = null,
    @field:Column(length = 3)
    val currency: String? = null,
    @field:Enumerated(EnumType.STRING)
    @field:Column(length = 32)
    val status: PaymentStatus? = null,
    @field:Enumerated(EnumType.STRING)
    @field:Column(length = 32)
    val refundReason: RefundReason? = null,
) {
    @field:Id
    @field:GeneratedValue(strategy = GenerationType.IDENTITY)
    var id: Long? = null
        protected set

    @field:CreationTimestamp
    @field:Column(nullable = false, updatable = false)
    var recordedAt: Instant? = null
        protected set

    fun toPaymentRecord(): PaymentRecord {
        check(type == ReservationPaymentTxType.APPROVED)
        val payment = VerifiedPayment(provider, requireNotNull(paymentId), reservationId, amount, requireNotNull(currency), pgOrderId)
        return PaymentRecord(payment, requireNotNull(status), refundReason)
    }

    fun canceled(cancellationId: String, canceledAmount: Long): ReservationPaymentTx {
        require(type == ReservationPaymentTxType.APPROVED)
        require(cancellationId.isNotBlank() && cancellationId.length <= 255)
        require(canceledAmount in 1..amount)
        return ReservationPaymentTx(
            reservationId, ReservationPaymentTxType.CANCELED, provider, canceledAmount,
            attemptId = attemptId, pgOrderId = pgOrderId, paymentId = paymentId,
            cancellationId = cancellationId, currency = currency,
        )
    }

    companion object {
        fun prepare(reservationId: Long, attemptId: UUID, amount: Long): ReservationPaymentTx {
            require(reservationId > 0 && amount > 0)
            val pgOrderId = "OR-${UUID.randomUUID()}"
            return ReservationPaymentTx(
                reservationId, ReservationPaymentTxType.APPROVAL_PREPARED, "demo-pg", amount,
                attemptId = attemptId, pgOrderId = pgOrderId, approvalIdempotencyKey = "confirm-$pgOrderId",
            )
        }

        fun approved(record: PaymentRecord, preparation: ReservationPaymentTx?): ReservationPaymentTx {
            val payment = record.payment
            require(preparation?.pgOrderId == payment.pgOrderId)
            if (preparation != null) {
                require(preparation.type == ReservationPaymentTxType.APPROVAL_PREPARED)
                require(preparation.reservationId == payment.reservationId && preparation.provider == payment.provider)
            }
            return ReservationPaymentTx(
                payment.reservationId, ReservationPaymentTxType.APPROVED, payment.provider, payment.amount,
                attemptId = preparation?.attemptId, pgOrderId = payment.pgOrderId, paymentId = payment.paymentId,
                currency = payment.currency, status = record.status, refundReason = record.refundReason,
            )
        }
    }
}
