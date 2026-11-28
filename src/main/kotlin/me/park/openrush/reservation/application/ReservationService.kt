package me.park.openrush.reservation.application

import me.park.openrush.concert.application.provided.GetPerformanceUseCase
import me.park.openrush.concert.application.provided.PerformanceDetails
import me.park.openrush.inventory.application.provided.*
import me.park.openrush.inventory.domain.exception.InventoryRuleViolationException
import me.park.openrush.inventory.domain.SeatStatus
import me.park.openrush.reservation.application.exception.PaymentRecordConflictException
import me.park.openrush.reservation.application.provided.ReservationPaymentTransactionsUseCase
import me.park.openrush.reservation.application.provided.ReservationPaymentApprovalResult
import me.park.openrush.reservation.domain.PaymentStatus
import me.park.openrush.reservation.domain.RefundReason
import me.park.openrush.reservation.application.provided.RecordPaymentSuccessCommand
import me.park.openrush.quota.application.provided.AllocatePurchaseQuotaUseCase
import me.park.openrush.quota.application.provided.AllocateQuotaCommand
import me.park.openrush.quota.domain.exception.QuotaRuleViolationException
import me.park.openrush.reservation.application.exception.PaymentAttemptConflictException
import me.park.openrush.reservation.application.exception.ReservationIdempotencyConflictException
import me.park.openrush.reservation.application.provided.*
import me.park.openrush.reservation.application.required.ConfigureExpirationTransactionPort
import me.park.openrush.reservation.application.required.ReservationCreationRecord
import me.park.openrush.reservation.application.required.ReservationRepositoryPort
import me.park.openrush.reservation.domain.Reservation
import me.park.openrush.reservation.domain.ReservationBookingPolicy
import me.park.openrush.reservation.domain.ReservationSeat
import me.park.openrush.reservation.domain.ReservationStatus
import me.park.openrush.reservation.domain.exception.ReservationRuleViolationException
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import java.time.Clock
import java.time.Duration

@Service
class ReservationService(
    private val getPerformanceUseCase: GetPerformanceUseCase,
    private val getSeatsUseCase: GetSeatsUseCase,
    private val allocatePurchaseQuotaUseCase: AllocatePurchaseQuotaUseCase,
    private val reservationRepository: ReservationRepositoryPort,
    private val holdSeatsUseCase: HoldSeatsUseCase,
    private val clock: Clock,
    private val confirmSeatsUseCase: ConfirmSeatsUseCase,
    private val releaseReservationHoldResources: ReleaseReservationHoldResources,
    private val configureExpirationTransactionPort: ConfigureExpirationTransactionPort,
    private val reservationPaymentTransactionsUseCase: ReservationPaymentTransactionsUseCase,
    @Value("\${reservation.hold-duration-ms:300000}") holdDurationMillis: Long,
) : GetReservationUseCase, ConfirmReservationUseCase, CancelReservationHoldUseCase,
    ExpireReservationHoldUseCase, PrepareReservationPaymentTxUseCase, RecordPaymentSuccessUseCase {
    private val holdDuration = Duration.ofMillis(holdDurationMillis)

    init {
        require(holdDurationMillis > 0) { "선점 시간은 양수여야 합니다." }
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    fun create(createReservationCommand: CreateReservationCommand, admit: (() -> Unit)? = null): CreateReservationResult {
        val key = createReservationCommand.idempotencyKey ?: return createNew(createReservationCommand)
        val request = "${createReservationCommand.performanceId}:${createReservationCommand.seatIds.sorted().joinToString(",")}"
        reservationRepository.findCreationRecord(createReservationCommand.userId, key)?.let { return replay(it, request) }

        admit?.invoke()
        if (!reservationRepository.claimCreationRequest(createReservationCommand.userId, key, request)) {
            return replay(checkNotNull(reservationRepository.findCreationRecord(createReservationCommand.userId, key)), request)
        }

        val result = createNew(createReservationCommand)
        reservationRepository.completeCreationRequest(createReservationCommand.userId, key, result)
        return result
    }

    private fun replay(reservationCreationRecord: ReservationCreationRecord, request: String): CreateReservationResult {
        if (reservationCreationRecord.request != request) throw ReservationIdempotencyConflictException()
        return reservationCreationRecord.result
    }

    private fun createNew(createReservationCommand: CreateReservationCommand): CreateReservationResult = try {
        createReservation(createReservationCommand)
    } catch (exception: InventoryRuleViolationException) {
        throw ReservationRuleViolationException(requireNotNull(exception.message))
    } catch (exception: QuotaRuleViolationException) {
        throw ReservationRuleViolationException(requireNotNull(exception.message))
    }

    private fun createReservation(createReservationCommand: CreateReservationCommand): CreateReservationResult {
        val performance = loadPerformance(createReservationCommand.performanceId)
        val selectedSeats = loadRequestedSeats(createReservationCommand)

        val reservation = Reservation.create(
            bookingPolicy = ReservationBookingPolicy(
                performance.performanceId,
                performance.bookingOpensAt,
                performance.bookingClosesAt,
                performance.maxTicketsPerUser
            ),
            userId = createReservationCommand.userId,
            seats = selectedSeats.map {
                ReservationSeat(
                    it.seatId,
                    it.performanceId,
                    it.amount,
                    it.status == SeatStatus.AVAILABLE
                )
            },
            now = clock.reservationTimestamp(),
            holdDuration = holdDuration,
        )

        allocatePurchaseQuotaUseCase.allocate(
            AllocateQuotaCommand(
                reservation.userId,
                performance.concertId,
                reservation.quantity
            )
        )

        val savedReservation = reservationRepository.save(reservation)
        val heldSeats = holdSeatsUseCase.hold(
            HoldSeatsCommand(
                requireNotNull(savedReservation.id),
                savedReservation.performanceId,
                savedReservation.items.map { it.seatId })
        )

        return CreateReservationResult.from(savedReservation, heldSeats)
    }

    private fun loadPerformance(performanceId: Long): PerformanceDetails =
        getPerformanceUseCase.findPerformance(performanceId)
            ?: throw ReservationRuleViolationException("회차가 존재하지 않습니다.")

    private fun loadRequestedSeats(createReservationCommand: CreateReservationCommand): List<SeatDetails> {
        val seats = getSeatsUseCase.findSeats(createReservationCommand.performanceId, createReservationCommand.seatIds)

        if (seats.size != createReservationCommand.seatIds.size) {
            throw ReservationRuleViolationException("요청한 좌석이 해당 회차에 존재하지 않습니다.")
        }

        return seats
    }

    @Transactional(readOnly = true)
    override fun find(reservationId: Long, userId: Long): ReservationDetails? {
        require(reservationId > 0 && userId > 0) { "예매 ID와 사용자 ID는 양수여야 합니다." }
        val reservation = reservationRepository.findReservation(reservationId)
            ?.takeIf { it.userId == userId } ?: return null

        return ReservationDetails(
            reservationId = reservationId,
            performanceId = reservation.performanceId,
            status = reservation.status,
            heldAt = reservation.heldAt,
            expiresAt = reservation.expiresAt,
            confirmedAt = reservation.confirmedAt,
            quantity = reservation.quantity,
            totalAmount = reservation.totalAmount,
            seatIds = reservation.items.map { it.seatId }.sorted(),
        )
    }

    @Transactional(noRollbackFor = [ReservationRuleViolationException::class])
    override fun confirm(confirmReservationCommand: ConfirmReservationCommand) {
        val reservation = reservationRepository.findReservation(confirmReservationCommand.reservationId)
            ?: throw ReservationRuleViolationException("예매가 존재하지 않습니다.")
        val now = clock.reservationTimestamp()
        reservation.validateConfirmation(confirmReservationCommand.userId, now)

        reservationRepository.confirmHeld(confirmReservationCommand.reservationId, confirmReservationCommand.userId, now)
        confirmSeatsUseCase.confirmSeats(
            confirmReservationCommand.reservationId,
            reservation.items.map { it.seatId },
            now,
        )
    }

    @Transactional
    override fun cancelHold(cancelReservationHoldCommand: CancelReservationHoldCommand) {
        val reservation = reservationRepository.findReservation(cancelReservationHoldCommand.reservationId)
            ?: throw ReservationRuleViolationException("예매가 존재하지 않습니다.")
        val now = clock.reservationTimestamp()
        reservation.validateHoldCancellation(cancelReservationHoldCommand.userId, now)

        // 상태 전환에 성공한 요청만 자원을 반환한다.
        reservationRepository.cancelHeld(cancelReservationHoldCommand.reservationId, cancelReservationHoldCommand.userId, now)
        releaseReservationHoldResources.release(reservation)
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    override fun expireHold(reservationId: Long): Boolean {
        require(reservationId > 0) { "예매 ID는 양수여야 합니다." }
        configureExpirationTransactionPort.configure()
        if (!reservationRepository.expireHeld(reservationId, clock.reservationTimestamp())) return false
        val reservation = checkNotNull(reservationRepository.findReservation(reservationId)) {
            "만료 전환한 예매를 찾을 수 없습니다."
        }
        // 반환 실패 시 EXPIRED 전환도 함께 롤백한다.
        releaseReservationHoldResources.release(reservation)
        return true
    }

    @Transactional
    override fun prepare(approveReservationPaymentCommand: ApproveReservationPaymentCommand): PreparedReservationPayment {
        val reservation = reservationRepository.lockOwnedReservation(approveReservationPaymentCommand.reservationId, approveReservationPaymentCommand.userId)
            ?: throw ReservationRuleViolationException("결제할 수 있는 예매가 없습니다.")
        if (reservation.status == ReservationStatus.CANCELLED && reservation.confirmedAt != null) {
            throw ReservationRuleViolationException("취소된 예약은 다시 확정할 수 없습니다.")
        }
        val now = clock.reservationTimestamp()
        val approvalAllowed =
            reservation.status == ReservationStatus.HELD && now >= reservation.heldAt && now < reservation.expiresAt
        reservationPaymentTransactionsUseCase.findPreparation(approveReservationPaymentCommand.reservationId, approveReservationPaymentCommand.attemptNumber)?.let {
            if (it.requestedPaymentKey != approveReservationPaymentCommand.paymentKey) throw PaymentAttemptConflictException()
            return PreparedReservationPayment(it, approvalAllowed)
        }
        if (!approvalAllowed) throw ReservationRuleViolationException("유효한 선점 예매만 결제할 수 있습니다.")
        val amount = reservation.totalAmount
        if (amount <= 0) throw ReservationRuleViolationException("결제 금액은 양수여야 합니다.")
        val paymentPreparation = reservationPaymentTransactionsUseCase.prepare(
            approveReservationPaymentCommand.reservationId,
            approveReservationPaymentCommand.attemptNumber,
            amount,
            approveReservationPaymentCommand.paymentKey,
            reservation.expiresAt,
        )

        return PreparedReservationPayment(paymentPreparation, true)
    }

    @Transactional
    override fun record(recordPaymentSuccessCommand: RecordPaymentSuccessCommand): ReservationPaymentApprovalResult {
        val reservation = requireNotNull(reservationRepository.lockReservation(recordPaymentSuccessCommand.reservationId)) {
            "결제에 연결된 예매가 존재하지 않습니다."
        }
        reservationPaymentTransactionsUseCase.findApproved(recordPaymentSuccessCommand.provider, recordPaymentSuccessCommand.paymentId)?.let { existing ->
            if (existing.payment != recordPaymentSuccessCommand) throw PaymentRecordConflictException()
            return existing
        }

        val paymentApprovalResult = if (recordPaymentSuccessCommand.currency != "KRW" || recordPaymentSuccessCommand.amount != reservation.totalAmount) {
            ReservationPaymentApprovalResult(recordPaymentSuccessCommand, PaymentStatus.REFUND_REQUIRED, RefundReason.PAYMENT_MISMATCH)
        } else {
            try {
                confirm(ConfirmReservationCommand(reservation.userId, recordPaymentSuccessCommand.reservationId))
                ReservationPaymentApprovalResult(recordPaymentSuccessCommand, PaymentStatus.RESERVATION_CONFIRMED)
            } catch (_: ReservationRuleViolationException) {
                ReservationPaymentApprovalResult(recordPaymentSuccessCommand, PaymentStatus.REFUND_REQUIRED, RefundReason.RESERVATION_UNAVAILABLE)
            }
        }
        return reservationPaymentTransactionsUseCase.record(paymentApprovalResult)
    }
}
