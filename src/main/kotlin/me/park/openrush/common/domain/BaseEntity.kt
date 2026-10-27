package me.park.openrush.common.domain

import jakarta.persistence.Column
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.MappedSuperclass
import org.hibernate.annotations.CreationTimestamp
import org.hibernate.annotations.UpdateTimestamp
import java.time.Instant

@MappedSuperclass
abstract class BaseEntity {
    @field:Id
    @field:GeneratedValue(strategy = GenerationType.IDENTITY)
    var id: Long? = null
        protected set

    @field:CreationTimestamp
    @field:Column(name = "created_at", nullable = false, updatable = false)
    var createdAt: Instant? = null
        protected set

    @field:UpdateTimestamp
    @field:Column(name = "modified_at", nullable = false)
    var modifiedAt: Instant? = null
        protected set
}
