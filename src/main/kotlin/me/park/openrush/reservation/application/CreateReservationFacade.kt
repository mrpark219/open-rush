package me.park.openrush.reservation.application

import jakarta.persistence.LockTimeoutException
import jakarta.persistence.PessimisticLockException
import me.park.openrush.common.exception.ConcurrencyException
import me.park.openrush.reservation.application.exception.PurchaseQuotaCreationConflict
import me.park.openrush.reservation.application.port.input.CreateReservationCommand
import me.park.openrush.reservation.application.port.input.CreateReservationResult
import me.park.openrush.reservation.application.port.input.CreateReservationUseCase
import org.springframework.dao.ConcurrencyFailureException
import org.springframework.stereotype.Service

@Service
class CreateReservationFacade(
    private val createReservationService: CreateReservationService,
) : CreateReservationUseCase {
    override fun create(command: CreateReservationCommand): CreateReservationResult {
        try {
            return createWithQuotaCreationRetry(command)
        } catch (exception: RuntimeException) {
            throw when (exception) {
                is PurchaseQuotaCreationConflict,
                is ConcurrencyFailureException,
                is PessimisticLockException,
                is LockTimeoutException -> ConcurrencyException(exception)
                else -> exception
            }
        }
    }

    private fun createWithQuotaCreationRetry(command: CreateReservationCommand): CreateReservationResult =
        try {
            createReservationService.create(command)
        } catch (_: PurchaseQuotaCreationConflict) {
            createReservationService.create(command)
        }
}
