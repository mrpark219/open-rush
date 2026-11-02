package me.park.openrush.reservation.application

import jakarta.persistence.LockTimeoutException
import jakarta.persistence.OptimisticLockException
import jakarta.persistence.PessimisticLockException
import me.park.openrush.common.exception.ConcurrencyException
import me.park.openrush.reservation.application.exception.PurchaseQuotaCreationConflict
import me.park.openrush.reservation.application.port.input.CreateReservationCommand
import me.park.openrush.reservation.application.port.input.CreateReservationResult
import me.park.openrush.reservation.application.port.input.CreateReservationUseCase
import org.springframework.dao.ConcurrencyFailureException
import org.springframework.dao.OptimisticLockingFailureException
import org.springframework.stereotype.Service

@Service
class CreateReservationFacade(
    private val createReservationService: CreateReservationService,
) : CreateReservationUseCase {
    override fun create(command: CreateReservationCommand): CreateReservationResult {
        try {
            return createWithRetry(command)
        } catch (exception: RuntimeException) {
            throw when (exception) {
                is PurchaseQuotaCreationConflict,
                is ConcurrencyFailureException,
                is OptimisticLockException,
                is PessimisticLockException,
                is LockTimeoutException -> ConcurrencyException(exception)
                else -> exception
            }
        }
    }

    private fun createWithRetry(command: CreateReservationCommand): CreateReservationResult {
        var retryCount = 0
        var quotaCreationRetried = false

        while (true) {
            try {
                return createReservationService.create(command)
            } catch (exception: RuntimeException) {
                when (exception) {
                    is OptimisticLockingFailureException, is OptimisticLockException -> Unit
                    is PurchaseQuotaCreationConflict -> {
                        if (quotaCreationRetried) throw exception
                        quotaCreationRetried = true
                    }
                    else -> throw exception
                }

                if (retryCount == MAX_RETRIES) throw exception
                retryCount++
            }
        }
    }

    private companion object {
        const val MAX_RETRIES = 3
    }
}
