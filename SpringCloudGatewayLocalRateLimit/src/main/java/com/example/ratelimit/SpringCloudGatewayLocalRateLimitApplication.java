package com.example.ratelimit;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.cloud.gateway.filter.ratelimit.KeyResolver;
import org.springframework.context.annotation.Bean;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

/**
 * Official spring cloud gateway rate limit with resilient4j (changed the id-ratelimit mapping in map)
 */
@SpringBootApplication
@EnableConfigurationProperties()
public class SpringCloudGatewayLocalRateLimitApplication {

    public static void main(String[] args) {
        SpringApplication.run(SpringCloudGatewayLocalRateLimitApplication.class, args);
    }

    //https://github.com/spring-cloud/spring-cloud-gateway/pull/1703
    @Bean
    public KeyResolver keyResolver() {
        return exchange -> Mono.just(exchange.getRequest().getRemoteAddress().getHostName()).log();
    }

    @Bean
    WebClient.Builder webclientBuilder() {
        return WebClient.builder();
    }

}
