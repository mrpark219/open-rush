package me.park.openrush.quota.application.provided

/** 사용자가 해당 공연에서 확보한 구매 한도를 요청 수량만큼 반환한다. */
fun interface ReleasePurchaseQuotaUseCase { fun releaseQuota(userId: Long, concertId: Long, quantity: Int) }
