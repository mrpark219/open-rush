package me.park.openrush.reservation.application

import me.park.openrush.payment.application.provided.ApprovedPayment
import me.park.openrush.payment.application.provided.PaymentApprovalCommand
import me.park.openrush.reservation.application.provided.PaymentPreparation
import me.park.openrush.reservation.application.provided.RecordPaymentSuccessCommand
import me.park.openrush.reservation.domain.ReservationPaymentTransaction

internal fun ReservationPaymentTransaction.toPreparation() = PaymentPreparation(
    id = requireNotNull(id),
    reservationId = reservationId,
    attemptNumber = requireNotNull(attemptNumber),
    provider = provider,
    amount = amount,
    pgOrderNumber = requireNotNull(pgOrderNumber),
    approvalIdempotencyKey = requireNotNull(approvalIdempotencyKey),
    requestedPaymentKey = requireNotNull(requestedPaymentKey),
)

internal fun PaymentPreparation.toPaymentApprovalCommand() = PaymentApprovalCommand(
    provider = provider,
    orderNumber = pgOrderNumber,
    paymentKey = requestedPaymentKey,
    amount = amount,
    idempotencyKey = approvalIdempotencyKey,
)

internal fun ApprovedPayment.toRecordPaymentSuccessCommand(reservationId: Long) = RecordPaymentSuccessCommand(
    provider = provider,
    paymentId = paymentKey,
    reservationId = reservationId,
    amount = amount,
    currency = currency,
    pgOrderNumber = orderNumber,
)
