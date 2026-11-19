package me.park.openrush.queue.domain

import java.time.Instant
import java.util.UUID

enum class QueueError(val message: String) {
    QUEUE_FULL("대기열이 가득 찼습니다."),
    QUEUE_TICKET_NOT_FOUND("대기 번호가 없거나 만료되었습니다."),
    QUEUE_ADMISSION_REQUIRED("유효한 입장 허가가 필요합니다."),
}
