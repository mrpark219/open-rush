package me.park.openrush.reservation.application.provided

/** 실행 시각이 지난 환불 작업을 점유하고, PG 환불 결과 또는 다음 실행 시각을 기록한다. */
interface ProcessReservationRefundJobsUseCase {
    fun processDueRefunds()
}
