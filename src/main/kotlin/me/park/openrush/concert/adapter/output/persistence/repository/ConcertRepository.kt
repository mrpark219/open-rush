package me.park.openrush.concert.adapter.output.persistence.repository

import me.park.openrush.concert.domain.Concert
import org.springframework.data.jpa.repository.JpaRepository

interface ConcertRepository : JpaRepository<Concert, Long>
