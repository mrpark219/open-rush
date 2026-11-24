package me.park.openrush.queue.adapter.input.web

import me.park.openrush.queue.application.exception.QueueUnavailableException
import me.park.openrush.queue.application.provided.GetQueueTicketUseCase
import me.park.openrush.queue.application.provided.JoinQueueUseCase
import me.park.openrush.support.web.GlobalExceptionHandler
import org.junit.jupiter.api.Test
import org.mockito.Mockito.doThrow
import org.mockito.Mockito.mock
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.test.web.servlet.setup.MockMvcBuilders

class ReservationQueueControllerTest {
    private val joinQueueUseCase = mock(JoinQueueUseCase::class.java)
    private val mockMvc = MockMvcBuilders.standaloneSetup(
        ReservationQueueController(
            joinQueueUseCase = joinQueueUseCase,
            getQueueTicketUseCase = mock(GetQueueTicketUseCase::class.java),
        ),
    ).setControllerAdvice(GlobalExceptionHandler()).build()

    @Test
    fun `Redis에 연결할 수 없으면 대기열 장애 응답을 반환한다`() {
        doThrow(QueueUnavailableException(IllegalStateException("Redis unavailable")))
            .`when`(joinQueueUseCase).join(1)

        mockMvc.perform(
            post("/reservation-queue")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"userId":1}"""),
        )
            .andExpect(status().isServiceUnavailable)
            .andExpect(jsonPath("$.code").value("QUEUE_UNAVAILABLE"))
    }
}
