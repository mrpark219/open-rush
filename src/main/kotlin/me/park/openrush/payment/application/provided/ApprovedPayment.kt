package me.park.openrush.payment.application.provided

/** PG가 승인한 결제 정보다. 예약 연결과 후속 처리 상태는 호출자가 관리한다. */
data class ApprovedPayment(
    val provider: String,
    val paymentKey: String,
    val amount: Long,
    val currency: String,
    val orderNumber: String,
) {
    init {
        require(provider.isNotBlank() && provider.length <= 32)
        require(paymentKey.isNotBlank() && paymentKey.length <= 255)
        require(amount >= 0)
        require(currency.length == 3 && currency.all { it in 'A'..'Z' })
        require(orderNumber.isNotBlank() && orderNumber.length <= 64)
    }
}
