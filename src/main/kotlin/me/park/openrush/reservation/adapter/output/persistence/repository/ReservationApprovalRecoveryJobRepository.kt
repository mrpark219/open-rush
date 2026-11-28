package me.park.openrush.reservation.adapter.output.persistence.repository

import me.park.openrush.reservation.domain.ReservationApprovalRecoveryJob
import org.springframework.data.jpa.repository.JpaRepository

interface ReservationApprovalRecoveryJobRepository :
    JpaRepository<ReservationApprovalRecoveryJob, Long>, ReservationApprovalRecoveryJobRepositoryCustom
