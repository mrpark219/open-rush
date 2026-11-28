package me.park.openrush.reservation.application.provided

/** 미확인 승인 주문을 PG에서 조회하고 확인된 결제 결과를 예약에 반영한다. */
interface RecoverReservationPaymentApprovalsUseCase {
    fun recoverDueApprovals()
}
