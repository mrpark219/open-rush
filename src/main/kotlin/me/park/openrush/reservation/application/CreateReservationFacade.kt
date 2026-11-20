package me.park.openrush.reservation.application

import jakarta.persistence.LockTimeoutException
import jakarta.persistence.PessimisticLockException
import me.park.openrush.quota.application.exception.PurchaseQuotaCreationConflictException
import me.park.openrush.reservation.application.exception.ConcurrencyException
import me.park.openrush.reservation.application.provided.CreateReservationCommand
import me.park.openrush.reservation.application.provided.CreateReservationResult
import me.park.openrush.reservation.application.provided.CreateReservationUseCase
import org.springframework.dao.ConcurrencyFailureException
import org.springframework.stereotype.Service

@Service
class CreateReservationFacade(
    private val reservationService: ReservationService,
) : CreateReservationUseCase {
    override fun create(
        createReservationCommand: CreateReservationCommand,
        acquireAdmission: (() -> AutoCloseable)?
    ): CreateReservationResult {
        var admissionPermit: AutoCloseable? = null
        val admitReservation: (() -> Unit)? = acquireAdmission?.let { acquireAdmissionPermit ->
            { if (admissionPermit == null) admissionPermit = acquireAdmissionPermit() }
        }
        try {
            if (createReservationCommand.idempotencyKey == null) admitReservation?.invoke()
            return createWithQuotaCreationRetry(createReservationCommand, admitReservation)
        } catch (exception: RuntimeException) {
            throw when (exception) {
                is PurchaseQuotaCreationConflictException,
                is ConcurrencyFailureException,
                is PessimisticLockException,
                is LockTimeoutException -> ConcurrencyException(exception)

                else -> exception
            }
        } finally {
            admissionPermit?.close()
        }
    }

    private fun createWithQuotaCreationRetry(
        createReservationCommand: CreateReservationCommand,
        admitReservation: (() -> Unit)?
    ): CreateReservationResult =
        try {
            reservationService.create(createReservationCommand, admitReservation)
        } catch (_: PurchaseQuotaCreationConflictException) {
            reservationService.create(createReservationCommand, admitReservation)
        }
}
