package me.park.openrush.queue.adapter.input.scheduling

import me.park.openrush.queue.application.provided.AdvanceQueueUseCase
import me.park.openrush.queue.application.exception.QueueUnavailableException
import org.slf4j.LoggerFactory
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component

@Component
@ConditionalOnProperty(prefix = "queue", name = ["enabled"], havingValue = "true")
class QueueAdmissionScheduler(
    private val advanceQueueUseCase: AdvanceQueueUseCase,
) {
    private val logger = LoggerFactory.getLogger(javaClass)

    @Scheduled(fixedDelayString = "\${queue.advance-interval:10ms}")
    fun advance() {
        try {
            advanceQueueUseCase.advance()
        } catch (exception: QueueUnavailableException) {
            logger.warn("Failed to advance reservation queue", exception)
        }
    }
}
