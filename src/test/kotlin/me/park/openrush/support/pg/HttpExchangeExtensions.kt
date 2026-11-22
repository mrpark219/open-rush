package me.park.openrush.support.pg

import com.sun.net.httpserver.HttpExchange

fun HttpExchange.reply(status: Int, body: String) {
    val responseBytes = body.toByteArray(Charsets.UTF_8)
    responseHeaders.set("Content-Type", "application/json; charset=UTF-8")
    sendResponseHeaders(status, responseBytes.size.toLong())
    responseBody.use { it.write(responseBytes) }
}
