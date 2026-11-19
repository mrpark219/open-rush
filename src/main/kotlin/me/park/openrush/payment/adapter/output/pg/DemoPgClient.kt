package me.park.openrush.payment.adapter.output.pg

import org.springframework.cloud.openfeign.FeignClient
import org.springframework.http.MediaType
import org.springframework.web.bind.annotation.*

@FeignClient(
    name = "demo-pg",
    url = "\${payment.demo-pg.base-url}",
    configuration = [DemoPgErrorConfiguration::class],
)
interface DemoPgClient {
    @PostMapping("/v1/payments/{paymentKey}/cancel", consumes = [MediaType.APPLICATION_JSON_VALUE])
    fun cancel(
        @PathVariable("paymentKey") paymentKey: String,
        @RequestHeader("Idempotency-Key") idempotencyKey: String,
        @RequestBody request: DemoPgCancelRequest,
    ): DemoPgPaymentResponse


    @PostMapping("/v1/payments/confirm", consumes = [MediaType.APPLICATION_JSON_VALUE])
    fun confirm(
        @RequestHeader("Idempotency-Key") idempotencyKey: String,
        @RequestBody request: DemoPgConfirmRequest,
    ): DemoPgPaymentResponse

    @GetMapping("/v1/payments/orders/{orderId}")
    fun findByOrder(@PathVariable("orderId") orderId: String): DemoPgPaymentResponse
}
