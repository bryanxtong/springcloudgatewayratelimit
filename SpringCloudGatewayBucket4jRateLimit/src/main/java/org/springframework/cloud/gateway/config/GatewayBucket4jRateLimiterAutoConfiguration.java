package org.springframework.cloud.gateway.config;

import com.github.benmanes.caffeine.cache.Caffeine;
import io.github.bucket4j.caffeine.CaffeineProxyManager;
import io.github.bucket4j.distributed.AsyncBucketProxy;
import io.github.bucket4j.distributed.proxy.AsyncProxyManager;
import io.github.bucket4j.distributed.remote.RemoteBucketState;
import org.springframework.boot.autoconfigure.AutoConfigureAfter;
import org.springframework.boot.autoconfigure.AutoConfigureBefore;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.cloud.gateway.filter.ratelimit.Bucket4jRateLimiter;
import org.springframework.cloud.gateway.support.ConfigurationService;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.reactive.DispatcherHandler;

import java.time.Duration;

/**
 * https://github.com/spring-cloud/spring-cloud-gateway/pull/2955/files
 *
 */
@Configuration(proxyBeanMethods = false)
@AutoConfigureBefore(GatewayAutoConfiguration.class)
@ConditionalOnClass({AsyncBucketProxy.class, DispatcherHandler.class})
@AutoConfigureAfter(GatewayRedisAutoConfiguration.class)
public class GatewayBucket4jRateLimiterAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    public Bucket4jRateLimiter bucket4jRateLimiter(AsyncProxyManager<String> asyncProxyManager, ConfigurationService configurationService) {
        return new Bucket4jRateLimiter(asyncProxyManager, configurationService);
    }

    @Bean
    @ConditionalOnMissingBean
    public AsyncProxyManager<String> asyncProxyManager() {
        Caffeine<String, RemoteBucketState> builder = (Caffeine) Caffeine.newBuilder().maximumSize(500);
        return new CaffeineProxyManager<>(builder, Duration.ofMinutes(1)).asAsync();
    }
}
