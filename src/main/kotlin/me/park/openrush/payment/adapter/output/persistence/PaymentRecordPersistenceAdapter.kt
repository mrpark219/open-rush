package me.park.openrush.payment.adapter.output.persistence

import me.park.openrush.payment.application.port.output.PaymentRecordPort
import me.park.openrush.payment.domain.PaymentRecord
import me.park.openrush.payment.domain.PaymentStatus
import me.park.openrush.payment.domain.RefundReason
import me.park.openrush.payment.domain.VerifiedPayment
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Repository

@Repository
class PaymentRecordPersistenceAdapter(private val jdbc: JdbcTemplate) : PaymentRecordPort {
    override fun claim(payment: VerifiedPayment): Boolean = jdbc.update(
        """
        INSERT INTO payment_records (provider, payment_id, reservation_id, amount, currency, status)
        VALUES (?, ?, ?, ?, ?, 'PROCESSING')
        ON CONFLICT (provider, payment_id) DO NOTHING
        """.trimIndent(),
        payment.provider, payment.paymentId, payment.reservationId, payment.amount, payment.currency,
    ) == 1

    override fun find(provider: String, paymentId: String): PaymentRecord? = jdbc.query(
        """
        SELECT reservation_id, amount, currency, status, refund_reason
        FROM payment_records WHERE provider = ? AND payment_id = ?
        """.trimIndent(),
        { row, _ ->
            PaymentRecord(
                payment = VerifiedPayment(provider, paymentId, row.getLong("reservation_id"), row.getLong("amount"), row.getString("currency")),
                status = PaymentStatus.valueOf(row.getString("status")),
                refundReason = row.getString("refund_reason")?.let(RefundReason::valueOf),
            )
        },
        provider, paymentId,
    ).singleOrNull()

    override fun complete(record: PaymentRecord) {
        val updated = jdbc.update(
            """
            UPDATE payment_records SET status = ?, refund_reason = ?
            WHERE provider = ? AND payment_id = ? AND status = 'PROCESSING'
            """.trimIndent(),
            record.status.name, record.refundReason?.name, record.payment.provider, record.payment.paymentId,
        )
        check(updated == 1) { "처리 중인 결제 기록을 찾을 수 없습니다." }
    }
}
