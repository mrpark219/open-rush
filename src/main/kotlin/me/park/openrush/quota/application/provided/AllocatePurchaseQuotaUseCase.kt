package me.park.openrush.quota.application.provided

/** 공연별 1인 구매 한도 내에서 요청 수량만큼 한도를 확보한다. */
fun interface AllocatePurchaseQuotaUseCase {
    fun allocate(command: AllocateQuotaCommand): AllocateQuotaResult
}
