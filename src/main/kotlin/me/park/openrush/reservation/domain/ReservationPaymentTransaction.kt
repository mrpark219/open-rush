package me.park.openrush.reservation.domain

import jakarta.persistence.*
import org.hibernate.annotations.CreationTimestamp
import java.time.Instant
import java.util.*

@Entity
@Table(name = "reservation_payment_tx")
class ReservationPaymentTransaction private constructor(
    @field:Column(nullable = false)
    val reservationId: Long,

    @field:Enumerated(EnumType.STRING)
    @field:Column(nullable = false, length = 32)
    val type: ReservationPaymentTransactionType,

    @field:Column(nullable = false, length = 32)
    val provider: String,

    @field:Column(nullable = false)
    val amount: Long,

    val attemptNumber: UUID? = null,

    @field:Column(length = 64)
    val pgOrderNumber: String? = null,

    @field:Column(length = 128)
    val approvalIdempotencyKey: String? = null,

    @field:Column(length = 200)
    val requestedPaymentKey: String? = null,

    @field:Column(length = 255)
    val paymentId: String? = null,

    @field:Column(length = 255)
    val cancellationId: String? = null,

    val cancellationNumber: UUID? = null,

    @field:Column(length = 128)
    val cancellationIdempotencyKey: String? = null,

    @field:Column(length = 255)
    val cancelReason: String? = null,

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

    fun prepareCancellation(
        cancellationNumber: UUID,
        cancelAmount: Long,
        cancelReason: String,
    ): ReservationPaymentTransaction {
        require(type == ReservationPaymentTransactionType.APPROVED)
        require(cancelAmount in 1..amount)
        require(cancelReason.isNotBlank() && cancelReason.length <= 255)

        return ReservationPaymentTransaction(
            reservationId = reservationId,
            type = ReservationPaymentTransactionType.CANCEL_PREPARED,
            provider = provider,
            amount = cancelAmount,
            attemptNumber = attemptNumber,
            pgOrderNumber = pgOrderNumber,
            paymentId = requireNotNull(paymentId),
            cancellationNumber = cancellationNumber,
            cancellationIdempotencyKey = "cancel-$cancellationNumber",
            cancelReason = cancelReason,
            currency = requireNotNull(currency),
        )
    }

    fun canceled(cancellationId: String): ReservationPaymentTransaction {
        require(type == ReservationPaymentTransactionType.CANCEL_PREPARED)
        require(cancellationId.isNotBlank() && cancellationId.length <= 255)

        return ReservationPaymentTransaction(
            reservationId = reservationId,
            type = ReservationPaymentTransactionType.CANCELED,
            provider = provider,
            amount = amount,
            attemptNumber = attemptNumber,
            pgOrderNumber = pgOrderNumber,
            paymentId = paymentId,
            cancellationId = cancellationId,
            cancellationNumber = cancellationNumber,
            cancellationIdempotencyKey = cancellationIdempotencyKey,
            cancelReason = cancelReason,
            currency = currency,
        )
    }

    companion object {
        fun prepare(
            reservationId: Long,
            attemptNumber: UUID,
            amount: Long,
            paymentKey: String,
        ): ReservationPaymentTransaction {
            require(reservationId > 0 && amount > 0)
            require(paymentKey.isNotBlank() && paymentKey.length <= 200)

            val pgOrderNumber = "OR-${UUID.randomUUID()}"

            return ReservationPaymentTransaction(
                reservationId = reservationId,
                type = ReservationPaymentTransactionType.APPROVAL_PREPARED,
                provider = "demo-pg",
                amount = amount,
                attemptNumber = attemptNumber,
                pgOrderNumber = pgOrderNumber,
                approvalIdempotencyKey = "confirm-$pgOrderNumber",
                requestedPaymentKey = paymentKey,
            )
        }

        fun approved(
            reservationId: Long,
            provider: String,
            paymentId: String,
            amount: Long,
            currency: String,
            pgOrderNumber: String?,
            status: PaymentStatus,
            refundReason: RefundReason?,
            preparation: ReservationPaymentTransaction?,
        ): ReservationPaymentTransaction {
            require(reservationId > 0 && amount >= 0)
            require(provider.isNotBlank() && provider.length <= 32)
            require(paymentId.isNotBlank() && paymentId.length <= 255)
            require(currency.length == 3 && currency.all { it in 'A'..'Z' })
            require(pgOrderNumber == null || (pgOrderNumber.isNotBlank() && pgOrderNumber.length <= 64))
            require((status == PaymentStatus.REFUND_REQUIRED) == (refundReason != null))
            require(preparation?.pgOrderNumber == pgOrderNumber)
            if (preparation != null) {
                require(preparation.type == ReservationPaymentTransactionType.APPROVAL_PREPARED)
                require(preparation.reservationId == reservationId && preparation.provider == provider)
            }

            return ReservationPaymentTransaction(
                reservationId = reservationId,
                type = ReservationPaymentTransactionType.APPROVED,
                provider = provider,
                amount = amount,
                attemptNumber = preparation?.attemptNumber,
                pgOrderNumber = pgOrderNumber,
                paymentId = paymentId,
                currency = currency,
                status = status,
                refundReason = refundReason,
            )
        }
    }
}
