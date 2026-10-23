package me.park.openrush

import org.springframework.boot.fromApplication
import org.springframework.boot.with


fun main(args: Array<String>) {
    fromApplication<OpenRushApplication>().with(TestcontainersConfiguration::class).run(*args)
}
