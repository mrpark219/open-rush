package me.park.openrush.concert.domain

import jakarta.persistence.*

@Entity
@Table(name = "concerts")
class Concert(
    title: String,
    maxTicketsPerUser: Int,
    description: String = ""
) {
    @field:Id
    @field:GeneratedValue(strategy = GenerationType.IDENTITY)
    var id: Long? = null
        protected set

    @field:Column(nullable = false)
    var title: String = title
        protected set

    @field:Column(nullable = false, length = 4000)
    var description: String = description
        protected set

    @field:Column(nullable = false)
    var maxTicketsPerUser: Int = maxTicketsPerUser
        protected set

    init {
        require(title.isNotBlank()) { "공연 제목은 비어 있을 수 없습니다." }
        require(maxTicketsPerUser > 0) { "1인 구매 한도는 양수여야 합니다." }
    }
}
