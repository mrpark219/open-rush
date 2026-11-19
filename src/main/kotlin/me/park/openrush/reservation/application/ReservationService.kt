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
    private val loadPerformancePort: GetPerformanceUseCase,
    private val loadPerformanceSeatsPort: GetSeatsUseCase,
    private val allocatePurchaseQuotaPort: AllocatePurchaseQuotaUseCase,
    private val reservations: ReservationRepositoryPort,
    private val holdPerformanceSeatsPort: HoldSeatsUseCase,
    private val clock: Clock,
    private val confirmPerformanceSeatsPort: ConfirmSeatsUseCase,
    private val holdResources: ReleaseReservationHoldResources,
    private val configureExpirationTransactionPort: ConfigureExpirationTransactionPort,
    private val paymentTransactions: ReservationPaymentTransactionsUseCase,
    @Value("\${reservation.hold-duration-ms:300000}") holdDurationMillis: Long,
) : GetReservationUseCase, ConfirmReservationUseCase, CancelReservationHoldUseCase,
    ExpireReservationHoldUseCase, PrepareReservationPaymentTxUseCase, RecordPaymentSuccessUseCase {
    private val holdDuration = Duration.ofMillis(holdDurationMillis)

    init {
        require(holdDurationMillis > 0) { "선점 시간은 양수여야 합니다." }
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    fun create(command: CreateReservationCommand, admit: (() -> Unit)? = null): CreateReservationResult {
        val key = command.idempotencyKey ?: return createNew(command)
        val request = "${command.performanceId}:${command.seatIds.sorted().joinToString(",")}"
        reservations.findCreationRecord(command.userId, key)?.let { return replay(it, request) }

        admit?.invoke()
        if (!reservations.claimCreationRequest(command.userId, key, request)) {
            return replay(checkNotNull(reservations.findCreationRecord(command.userId, key)), request)
        }

        val result = createNew(command)
        reservations.completeCreationRequest(command.userId, key, result)
        return result
    }

    private fun replay(record: ReservationCreationRecord, request: String): CreateReservationResult {
        if (record.request != request) throw ReservationIdempotencyConflictException()
        return record.result
    }

    private fun createNew(command: CreateReservationCommand): CreateReservationResult = try {
        createReservation(command)
    } catch (exception: InventoryRuleViolationException) {
        throw ReservationRuleViolationException(requireNotNull(exception.message))
    } catch (exception: QuotaRuleViolationException) {
        throw ReservationRuleViolationException(requireNotNull(exception.message))
    }

    private fun createReservation(command: CreateReservationCommand): CreateReservationResult {
        val performance = loadPerformance(command.performanceId)
        val selectedSeats = loadRequestedSeats(command)

        val reservation = Reservation.create(
            bookingPolicy = ReservationBookingPolicy(
                performance.performanceId,
                performance.bookingOpensAt,
                performance.bookingClosesAt,
                performance.maxTicketsPerUser
            ),
            userId = command.userId,
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

        allocatePurchaseQuotaPort.allocate(
            AllocateQuotaCommand(
                reservation.userId,
                performance.concertId,
                reservation.quantity
            )
        )

        val savedReservation = reservations.save(reservation)
        val heldSeats = holdPerformanceSeatsPort.hold(
            HoldSeatsCommand(
                requireNotNull(savedReservation.id),
                savedReservation.performanceId,
                savedReservation.items.map { it.seatId })
        )

        return CreateReservationResult.from(savedReservation, heldSeats)
    }

    private fun loadPerformance(performanceId: Long): PerformanceDetails =
        loadPerformancePort.findPerformance(performanceId)
            ?: throw ReservationRuleViolationException("회차가 존재하지 않습니다.")

    private fun loadRequestedSeats(command: CreateReservationCommand): List<SeatDetails> {
        val seats = loadPerformanceSeatsPort.findSeats(command.performanceId, command.seatIds)

        if (seats.size != command.seatIds.size) {
            throw ReservationRuleViolationException("요청한 좌석이 해당 회차에 존재하지 않습니다.")
        }

        return seats
    }

    @Transactional(readOnly = true)
    override fun find(reservationId: Long, userId: Long): ReservationDetails? {
        require(reservationId > 0 && userId > 0) { "예매 ID와 사용자 ID는 양수여야 합니다." }
        val reservation = reservations.findReservation(reservationId)
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
    override fun confirm(command: ConfirmReservationCommand) {
        val reservation = reservations.findReservation(command.reservationId)
            ?: throw ReservationRuleViolationException("예매가 존재하지 않습니다.")
        val now = clock.reservationTimestamp()
        reservation.validateConfirmation(command.userId, now)

        reservations.confirmHeld(command.reservationId, command.userId, now)
        confirmPerformanceSeatsPort.confirmSeats(
            command.reservationId,
            reservation.items.map { it.seatId },
            now,
        )
    }

    @Transactional
    override fun cancelHold(command: CancelReservationHoldCommand) {
        val reservation = reservations.findReservation(command.reservationId)
            ?: throw ReservationRuleViolationException("예매가 존재하지 않습니다.")
        val now = clock.reservationTimestamp()
        reservation.validateHoldCancellation(command.userId, now)

        // 상태 전환에 성공한 요청만 자원을 반환한다.
        reservations.cancelHeld(command.reservationId, command.userId, now)
        holdResources.release(reservation)
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    override fun expireHold(reservationId: Long): Boolean {
        require(reservationId > 0) { "예매 ID는 양수여야 합니다." }
        configureExpirationTransactionPort.configure()
        if (!reservations.expireHeld(reservationId, clock.reservationTimestamp())) return false
        val reservation = checkNotNull(reservations.findReservation(reservationId)) {
            "만료 전환한 예매를 찾을 수 없습니다."
        }
        // 반환 실패 시 EXPIRED 전환도 함께 롤백한다.
        holdResources.release(reservation)
        return true
    }

    @Transactional
    override fun prepare(command: ApproveReservationPaymentCommand): PreparedReservationPayment {
        val reservation = reservations.lockOwnedReservation(command.reservationId, command.userId)
            ?: throw ReservationRuleViolationException("결제할 수 있는 예매가 없습니다.")
        val now = clock.reservationTimestamp()
        val allowed =
            reservation.status == ReservationStatus.HELD && now >= reservation.heldAt && now < reservation.expiresAt
        paymentTransactions.findPreparation(command.reservationId, command.attemptNumber)?.let {
            if (it.requestedPaymentKey != command.paymentKey) throw PaymentAttemptConflictException()
            return PreparedReservationPayment(it, allowed)
        }
        if (!allowed) throw ReservationRuleViolationException("유효한 선점 예매만 결제할 수 있습니다.")
        val amount = reservation.totalAmount
        if (amount <= 0) throw ReservationRuleViolationException("결제 금액은 양수여야 합니다.")
        val tx = paymentTransactions.prepare(command.reservationId, command.attemptNumber, amount, command.paymentKey)
        return PreparedReservationPayment(tx, true)
    }

    @Transactional
    override fun record(payment: RecordPaymentSuccessCommand): ReservationPaymentApprovalResult {
        val reservation = requireNotNull(reservations.lockReservation(payment.reservationId)) {
            "결제에 연결된 예매가 존재하지 않습니다."
        }
        paymentTransactions.findApproved(payment.provider, payment.paymentId)?.let { existing ->
            if (existing.payment != payment) throw PaymentRecordConflictException()
            return existing
        }

        val record = if (payment.currency != "KRW" || payment.amount != reservation.totalAmount) {
            ReservationPaymentApprovalResult(payment, PaymentStatus.REFUND_REQUIRED, RefundReason.PAYMENT_MISMATCH)
        } else {
            try {
                confirm(ConfirmReservationCommand(reservation.userId, payment.reservationId))
                ReservationPaymentApprovalResult(payment, PaymentStatus.RESERVATION_CONFIRMED)
            } catch (_: ReservationRuleViolationException) {
                ReservationPaymentApprovalResult(payment, PaymentStatus.REFUND_REQUIRED, RefundReason.RESERVATION_UNAVAILABLE)
            }
        }
        return paymentTransactions.record(record)
    }
}
