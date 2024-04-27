package com.example.ratelimit;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cloud.gateway.filter.ratelimit.KeyResolver;
import org.springframework.context.annotation.Bean;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

/**
 * Official spring cloud gateway rate limit with redis lua script
 */
@SpringBootApplication
public class SpringCloudGatewayRateLimitApplication {

    public static void main(String[] args) {
        SpringApplication.run(SpringCloudGatewayRateLimitApplication.class, args);
    }

    //https://andifalk.gitbook.io/spring-cloud-gateway-workshop/hands-on-labs/lab2#step-3-configure-a-rate-limiter
    @Bean
    public KeyResolver keyResolver() {
        return exchange -> Mono.just(exchange.getRequest().getRemoteAddress().getHostName()).log();
    }

    @Bean
    WebClient.Builder webclientBuilder() {
        return WebClient.builder();
    }


}
