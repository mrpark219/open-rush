package me.park.openrush.queue.application.provided

/** 사용자와 대기표의 입장 자격을 검증하고 예매 처리 권한을 소비한다. 반환된 자원을 닫으면 처리 중 점유를 해제한다. */
fun interface ReservationAdmissionUseCase {
    fun consume(userId: Long, ticketHeader: String?): AutoCloseable
}
