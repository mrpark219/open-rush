package me.park.openrush.payment.adapter.output.pg

import me.park.openrush.payment.application.exception.PaymentGatewayUnavailableException
import me.park.openrush.payment.application.provided.PaymentCancellationCommand
import org.junit.jupiter.api.Test
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class DemoPaymentGatewayAdapterTest {
    private val demoPgClient = mock(DemoPgClient::class.java)
    private val paymentGateway = DemoPaymentGatewayAdapter(demoPgClient)
    private val paymentCancellationCommand = PaymentCancellationCommand(
        provider = "demo-pg",
        orderNumber = "ORDER-TEST",
        paymentKey = "PK-TEST",
        amount = 5000,
        currency = "KRW",
        reason = "CURRENT-REQUEST",
        idempotencyKey = "cancel-test",
        totalAmount = 15000,
        expectedBalanceAmount = 8000,
    )
    private val paymentResponse = DemoPgPaymentResponse(
        paymentKey = "PK-TEST",
        orderId = "ORDER-TEST",
        currency = "KRW",
        totalAmount = 15000,
        balanceAmount = 8000,
        status = "PARTIAL_CANCELED",
        cancels = listOf(
            DemoPgCancelResponse("OLD-CANCEL", 2000, "OLD-REQUEST", "DONE"),
            DemoPgCancelResponse("CURRENT-CANCEL", 5000, "CURRENT-REQUEST", "DONE"),
        ),
    )

    @Test
    fun `과거 취소 이력과 승인 총액이 있어도 이번 부분 취소 결과를 식별한다`() {
        respond(paymentResponse)
        assertEquals("CURRENT-CANCEL", paymentGateway.cancel(paymentCancellationCommand).cancellationId)
    }

    @Test
    fun `이번 취소와 같은 금액 및 사유가 여러 건이면 임의로 완료 처리하지 않는다`() {
        respond(paymentResponse.copy(cancels = paymentResponse.cancels + paymentResponse.cancels.last().copy(transactionKey = "AMBIGUOUS-CANCEL")))
        assertFailsWith<PaymentGatewayUnavailableException> { paymentGateway.cancel(paymentCancellationCommand) }
    }

    @Test
    fun `결제 식별 정보와 예상 잔액 및 상태가 다르면 완료로 처리하지 않는다`() {
        listOf(
            paymentResponse.copy(paymentKey = "OTHER-KEY"),
            paymentResponse.copy(orderId = "OTHER-ORDER"),
            paymentResponse.copy(currency = "USD"),
            paymentResponse.copy(totalAmount = 16000),
            paymentResponse.copy(balanceAmount = 9000),
            paymentResponse.copy(status = "CANCELED"),
            paymentResponse.copy(cancels = listOf(paymentResponse.cancels.last().copy(cancelStatus = "FAILED"))),
        ).forEach { invalidResponse ->
            respond(invalidResponse)
            assertFailsWith<PaymentGatewayUnavailableException> { paymentGateway.cancel(paymentCancellationCommand) }
        }
    }

    @Test
    fun `과거 부분 취소 이후 남은 금액을 취소하면 최종 취소 상태를 검증한다`() {
        val remainingCancellationCommand = paymentCancellationCommand.copy(amount = 13000, expectedBalanceAmount = 0)
        respond(
            paymentResponse.copy(
                balanceAmount = 0,
                status = "CANCELED",
                cancels = listOf(paymentResponse.cancels.first(), paymentResponse.cancels.last().copy(cancelAmount = 13000)),
            ),
            remainingCancellationCommand,
        )
        assertEquals("CURRENT-CANCEL", paymentGateway.cancel(remainingCancellationCommand).cancellationId)
    }

    private fun respond(
        response: DemoPgPaymentResponse,
        command: PaymentCancellationCommand = paymentCancellationCommand,
    ) {
        `when`(demoPgClient.cancel(command.paymentKey, command.idempotencyKey, DemoPgCancelRequest(command.reason, command.amount)))
            .thenReturn(response)
    }
}
