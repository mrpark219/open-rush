package me.park.openrush.reservation.adapter.output.pg

import feign.FeignException
import me.park.openrush.payment.adapter.output.pg.DemoPgClient
import me.park.openrush.payment.adapter.output.pg.DemoPgConfirmRequest
import me.park.openrush.payment.adapter.output.pg.DemoPgPaymentResponse
import me.park.openrush.reservation.application.port.output.PaymentGatewayRejected
import me.park.openrush.reservation.application.port.output.PaymentGatewayUnavailable
import me.park.openrush.reservation.application.port.output.ReservationPaymentGateway
import me.park.openrush.reservation.domain.payment.ReservationPaymentTx
import me.park.openrush.reservation.domain.payment.VerifiedPayment
import org.springframework.stereotype.Component

@Component
class DemoReservationPaymentAdapter(private val client: DemoPgClient) : ReservationPaymentGateway {
    override fun confirm(preparation: ReservationPaymentTx): VerifiedPayment {
        val response = try {
            client.confirm(
                requireNotNull(preparation.approvalIdempotencyKey),
                DemoPgConfirmRequest(requireNotNull(preparation.requestedPaymentKey), requireNotNull(preparation.pgOrderId), preparation.amount),
            )
        } catch (exception: FeignException) {
            if (exception.status() in 400..499 && exception.status() !in setOf(408, 409, 429)) {
                throw PaymentGatewayRejected(exception)
            }
            throw PaymentGatewayUnavailable(exception)
        }
        return verify(response, preparation)
    }

    override fun findByOrder(preparation: ReservationPaymentTx): VerifiedPayment? {
        val response = try {
            client.findByOrder(requireNotNull(preparation.pgOrderId))
        } catch (_: FeignException.NotFound) {
            return null
        } catch (exception: FeignException) {
            throw PaymentGatewayUnavailable(exception)
        }
        return verify(response, preparation)
    }

    private fun verify(response: DemoPgPaymentResponse, preparation: ReservationPaymentTx): VerifiedPayment {
        if (response.status != "DONE" || response.orderId != preparation.pgOrderId ||
            response.paymentKey != preparation.requestedPaymentKey
        ) throw PaymentGatewayUnavailable()
        return try {
            // 금액과 통화 불일치는 승인 사실을 보존한 뒤 환불 필요로 기록한다.
            VerifiedPayment(preparation.provider, response.paymentKey, preparation.reservationId,
                response.totalAmount, response.currency, response.orderId)
        } catch (exception: IllegalArgumentException) {
            throw PaymentGatewayUnavailable(exception)
        }
    }
}
