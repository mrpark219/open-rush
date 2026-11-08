package me.park.openrush.payment.domain

/** 결제사에서 결제 성공과 가맹점 주문의 예매 연결을 검증한 뒤 전달하는 값이다. */
data class VerifiedPayment(
    val provider: String,
    val paymentId: String,
    val reservationId: Long,
    val amount: Long,
    val currency: String,
) {
    init {
        require(provider.isNotBlank() && provider.length <= 32) { "결제사 식별자가 유효하지 않습니다." }
        require(paymentId.isNotBlank() && paymentId.length <= 255) { "결제 식별자가 유효하지 않습니다." }
        require(reservationId > 0) { "예매 ID는 양수여야 합니다." }
        require(amount >= 0) { "결제 금액은 음수일 수 없습니다." }
        require(currency.length == 3 && currency.all { it in 'A'..'Z' }) { "통화 코드는 대문자 세 글자여야 합니다." }
    }
}
