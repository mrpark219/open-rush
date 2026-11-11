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
    override fun create(command: CreateReservationCommand, acquireAdmission: (() -> AutoCloseable)?): CreateReservationResult {
        var permit: AutoCloseable? = null
        val admit: (() -> Unit)? = acquireAdmission?.let { acquire ->
            { if (permit == null) permit = acquire() }
        }
        try {
            if (command.idempotencyKey == null) admit?.invoke()
            return createWithQuotaCreationRetry(command, admit)
        } catch (exception: RuntimeException) {
            throw when (exception) {
                is PurchaseQuotaCreationConflict,
                is ConcurrencyFailureException,
                is PessimisticLockException,
                is LockTimeoutException -> ConcurrencyException(exception)
                else -> exception
            }
        } finally {
            permit?.close()
        }
    }

    private fun createWithQuotaCreationRetry(command: CreateReservationCommand, admit: (() -> Unit)?): CreateReservationResult =
        try {
            createReservationService.create(command, admit)
        } catch (_: PurchaseQuotaCreationConflict) {
            createReservationService.create(command, admit)
        }
}
