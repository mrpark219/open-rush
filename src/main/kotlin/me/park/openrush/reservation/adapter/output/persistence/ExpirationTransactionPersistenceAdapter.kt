package me.park.openrush.reservation.adapter.output.persistence

import jakarta.persistence.EntityManager
import me.park.openrush.reservation.application.port.output.ConfigureExpirationTransactionPort
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional

@Component
class ExpirationTransactionPersistenceAdapter(
    private val entityManager: EntityManager,
    @param:Value("\${reservation.expiration.lock-timeout-ms:1000}") private val lockTimeoutMillis: Int,
) : ConfigureExpirationTransactionPort {
    init {
        require(lockTimeoutMillis > 0) { "만료 잠금 대기 시간은 양수여야 합니다." }
    }

    @Transactional(propagation = Propagation.MANDATORY)
    override fun configure() {
        // 세 번째 인자가 true이므로 커밋·롤백 후 풀에 반환되는 연결에 설정을 남기지 않는다.
        entityManager.createNativeQuery("SELECT set_config('lock_timeout', :timeout, true)")
            .setParameter("timeout", "${lockTimeoutMillis}ms")
            .singleResult
    }
}
