package me.park.openrush.support.web

import me.park.openrush.payment.application.exception.PaymentGatewayRejectedException
import me.park.openrush.reservation.application.exception.PaymentRecordConflictException
import me.park.openrush.queue.domain.QueueError
import me.park.openrush.queue.domain.exception.QueueException
import me.park.openrush.reservation.application.exception.ConcurrencyException
import me.park.openrush.reservation.application.exception.PaymentAttemptConflictException
import me.park.openrush.reservation.application.exception.ReservationIdempotencyConflictException
import me.park.openrush.reservation.domain.exception.ReservationRuleViolationException
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.http.converter.HttpMessageNotReadableException
import org.springframework.web.bind.MethodArgumentNotValidException
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice

@RestControllerAdvice
class GlobalExceptionHandler {
    @ExceptionHandler(PaymentAttemptConflictException::class, PaymentRecordConflictException::class)
    fun paymentConflict(exception: RuntimeException): ResponseEntity<ErrorResponse> =
        ResponseEntity.status(HttpStatus.CONFLICT)
            .body(ErrorResponse("PAYMENT_CONFLICT", checkNotNull(exception.message)))

    @ExceptionHandler(PaymentGatewayRejectedException::class)
    fun paymentRejected(): ResponseEntity<ErrorResponse> =
        ResponseEntity.unprocessableContent().body(ErrorResponse("PAYMENT_REJECTED", "PG가 결제 승인을 거절했습니다."))

    @ExceptionHandler(ReservationIdempotencyConflictException::class)
    fun idempotency(exception: ReservationIdempotencyConflictException): ResponseEntity<ErrorResponse> =
        ResponseEntity.status(HttpStatus.CONFLICT)
            .body(ErrorResponse("IDEMPOTENCY_KEY_CONFLICT", checkNotNull(exception.message)))

    @ExceptionHandler(QueueException::class)
    fun queue(exception: QueueException): ResponseEntity<ErrorResponse> {
        val status =
            if (exception.error == QueueError.QUEUE_TICKET_NOT_FOUND) HttpStatus.NOT_FOUND else HttpStatus.TOO_MANY_REQUESTS
        return ResponseEntity.status(status).body(ErrorResponse(exception.error.name, exception.error.message))
    }

    @ExceptionHandler(ReservationRuleViolationException::class)
    fun rule(exception: ReservationRuleViolationException): ResponseEntity<ErrorResponse> =
        ResponseEntity.status(HttpStatus.CONFLICT)
            .body(ErrorResponse("RESERVATION_RULE_VIOLATION", checkNotNull(exception.message)))

    @ExceptionHandler(ConcurrencyException::class)
    fun concurrency(): ResponseEntity<ErrorResponse> = ResponseEntity.status(HttpStatus.CONFLICT)
        .body(ErrorResponse("CONCURRENCY_CONFLICT", "동시 요청과 충돌했습니다."))

    @ExceptionHandler(
        IllegalArgumentException::class,
        MethodArgumentNotValidException::class,
        HttpMessageNotReadableException::class,
    )
    fun invalid(): ResponseEntity<ErrorResponse> = ResponseEntity.badRequest()
        .body(ErrorResponse("INVALID_REQUEST", "요청 값을 확인해 주세요."))
}
