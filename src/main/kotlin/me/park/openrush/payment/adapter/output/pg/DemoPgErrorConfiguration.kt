package me.park.openrush.payment.adapter.output.pg

import feign.FeignException
import feign.codec.ErrorDecoder
import org.springframework.context.annotation.Bean

class DemoPgErrorConfiguration {
    @Bean
    fun demoPgErrorDecoder(): ErrorDecoder = ErrorDecoder { methodKey, response ->
        FeignException.errorStatus(methodKey, response)
    }
}
