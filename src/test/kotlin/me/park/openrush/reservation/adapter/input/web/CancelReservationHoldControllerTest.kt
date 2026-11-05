package me.park.openrush.reservation.adapter.input.web

import me.park.openrush.common.web.GlobalExceptionHandler
import me.park.openrush.reservation.application.port.input.CancelReservationHoldCommand
import me.park.openrush.reservation.application.port.input.CancelReservationHoldUseCase
import me.park.openrush.reservation.application.port.input.CreateReservationUseCase
import me.park.openrush.reservation.domain.exception.ReservationRuleViolation
import org.junit.jupiter.api.Test
import org.mockito.Mockito.doThrow
import org.mockito.Mockito.mock
import org.mockito.Mockito.verify
import org.mockito.Mockito.verifyNoInteractions
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.setup.MockMvcBuilders

class CancelReservationHoldControllerTest {
    private val holdCancellations = mock(CancelReservationHoldUseCase::class.java)
    private val mvc = MockMvcBuilders.standaloneSetup(
        ReservationController(mock(CreateReservationUseCase::class.java), holdCancellations),
    ).setControllerAdvice(GlobalExceptionHandler()).build()

    @Test
    fun `취소 성공은 204를 반환한다`() {
        mvc.perform(post("/reservations/10/cancel-hold").contentType(MediaType.APPLICATION_JSON).content("""{"userId":1}"""))
            .andExpect(status().isNoContent)
        verify(holdCancellations).cancelHold(CancelReservationHoldCommand(1, 10))
    }

    @Test
    fun `정책상 취소할 수 없는 예매는 409를 반환한다`() {
        doThrow(ReservationRuleViolation("취소 가능한 선점 예매가 아닙니다."))
            .`when`(holdCancellations).cancelHold(CancelReservationHoldCommand(1, 10))
        mvc.perform(post("/reservations/10/cancel-hold").contentType(MediaType.APPLICATION_JSON).content("""{"userId":1}"""))
            .andExpect(status().isConflict)
            .andExpect(jsonPath("$.code").value("RESERVATION_RULE_VIOLATION"))
    }

    @Test
    fun `잘못된 사용자와 예매 ID 및 누락된 요청 값은 400을 반환한다`() {
        listOf("/reservations/10/cancel-hold" to """{"userId":0}""",
            "/reservations/0/cancel-hold" to """{"userId":1}""",
            "/reservations/10/cancel-hold" to "{}",
            "/reservations/10/cancel-hold" to "invalid").forEach { (path, body) ->
            mvc.perform(post(path).contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isBadRequest)
        }
        verifyNoInteractions(holdCancellations)
    }
}
