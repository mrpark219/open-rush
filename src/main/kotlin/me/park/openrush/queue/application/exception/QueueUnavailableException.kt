package me.park.openrush.queue.application.exception

class QueueUnavailableException(cause: Throwable) : RuntimeException("대기열에 일시적으로 연결할 수 없습니다.", cause)
