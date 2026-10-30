package me.park.openrush.common.web

import me.park.openrush.common.exception.ConcurrencyException
import me.park.openrush.reservation.domain.exception.ReservationRuleViolation
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.http.converter.HttpMessageNotReadableException
import org.springframework.web.bind.MethodArgumentNotValidException
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice

@RestControllerAdvice
class GlobalExceptionHandler {
    @ExceptionHandler(ReservationRuleViolation::class)
    fun rule(exception: ReservationRuleViolation): ResponseEntity<ErrorResponse> =
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
