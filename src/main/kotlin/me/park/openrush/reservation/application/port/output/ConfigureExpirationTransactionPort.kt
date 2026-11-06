package me.park.openrush.reservation.application.port.output

fun interface ConfigureExpirationTransactionPort {
    /** 현재 만료 트랜잭션에만 잠금 대기 제한을 적용한다. 트랜잭션이 끝나면 원래 설정으로 돌아간다. */
    fun configure()
}
