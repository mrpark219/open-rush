package me.park.openrush.common.exception

/** DB 동시성 충돌을 나타낸다. 재시도는 롤백 후 새 트랜잭션에서 수행한다. */
class ConcurrencyException(cause: Throwable) : RuntimeException("동시 요청과 충돌했습니다.", cause)
