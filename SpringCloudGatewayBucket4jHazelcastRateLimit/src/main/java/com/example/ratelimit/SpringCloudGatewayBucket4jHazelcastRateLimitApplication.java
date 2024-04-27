package com.example.ratelimit;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.context.annotation.Bean;
import org.springframework.web.reactive.function.client.WebClient;

/**
 * Rate Limit with bucket4j spring boot starter
 */
@SpringBootApplication
@EnableCaching
public class SpringCloudGatewayBucket4jHazelcastRateLimitApplication {

    public static void main(String[] args) {
        SpringApplication.run(SpringCloudGatewayBucket4jHazelcastRateLimitApplication.class, args);
    }

    @Bean
    WebClient.Builder webclientBuilder() {
        return WebClient.builder();
    }

}
