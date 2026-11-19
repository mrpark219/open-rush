package me.park.openrush.queue.domain.exception

import me.park.openrush.queue.domain.QueueError

class QueueException(val error: QueueError) : RuntimeException(error.message)
