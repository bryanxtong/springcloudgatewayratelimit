/*
 * Copyright 2017-2019 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.springframework.cloud.gateway.filter.ratelimit;

import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

import io.github.resilience4j.ratelimiter.RateLimiter;
import io.github.resilience4j.ratelimiter.RateLimiterConfig;
import io.github.resilience4j.ratelimiter.RateLimiterRegistry;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
//import org.jetbrains.annotations.NotNull;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import reactor.core.publisher.Mono;

import org.springframework.beans.BeansException;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.cloud.gateway.route.RouteDefinitionRouteLocator;
import org.springframework.cloud.gateway.support.ConfigurationService;
import org.springframework.context.ApplicationContext;
import org.springframework.context.ApplicationContextAware;
import org.springframework.core.style.ToStringCreator;
import org.springframework.validation.annotation.Validated;

/**
 * @author Emmanouil Gkatziouras
 * https://github.com/spring-cloud/spring-cloud-gateway/pull/1703
 * https://github.com/gkatzioura/spring-cloud-gateway/tree/feature/defaultratelimiter
 */
@ConfigurationProperties("spring.cloud.gateway.local-rate-limiter")
public class LocalRateLimiter extends AbstractRateLimiter<LocalRateLimiter.Config>
        implements ApplicationContextAware {
    private Log log = LogFactory.getLog(getClass());

    /**
     * Local Rate Limiter property name.
     */
    public static final String CONFIGURATION_PROPERTY_NAME = "local-rate-limiter";

    /**
     * Remaining Rate Limit header name.
     */
    public static final String REMAINING_HEADER = "X-RateLimit-Remaining";

    /**
     * Replenish Rate Limit header name.
     */
    public static final String REPLENISH_RATE_HEADER = "X-RateLimit-Replenish-Rate";

    /**
     * Refresh period header name.
     */
    public static final String REFRESH_PERIOD_HEADER = "X-RateLimit-Refresh-Period";

    /**
     * Requested Tokens header name.
     */
    public static final String REQUESTED_TOKENS_HEADER = "X-RateLimit-Requested-Tokens";

    private AtomicBoolean initialized = new AtomicBoolean(false);

    // private Map<String, io.github.resilience4j.ratelimiter.RateLimiter> rateLimiterMap
    // = new ConcurrentHashMap<>();

    private Config defaultConfig;

    // configuration properties
    /**
     * Whether or not to include headers containing rate limiter information, defaults to
     * true.
     */
    private boolean includeHeaders = true;

    /**
     * The name of the header that returns number of remaining requests during the current
     * second.
     */
    private String remainingHeader = REMAINING_HEADER;

    /** The name of the header that returns the replenish rate configuration. */
    private String replenishRateHeader = REPLENISH_RATE_HEADER;

    /** The name of the header that returns the refresh period configuration. */
    private String refreshPeriodHeader = REFRESH_PERIOD_HEADER;

    /** The name of the header that returns the requested tokens configuration. */
    private String requestedTokensHeader = REQUESTED_TOKENS_HEADER;

    private Map<String,RateLimiter> routeRateLimitMap;

    public LocalRateLimiter(ConfigurationService configurationService) {
        super(Config.class, CONFIGURATION_PROPERTY_NAME, configurationService);
        this.initialized.compareAndSet(false, true);
        routeRateLimitMap = new HashMap<>();
    }

    /**
     * This creates an instance with default static configuration, useful in Java DSL.
     * @param defaultReplenishRate how many tokens per second in token-bucket algorithm.
     * algorithm.
     */
    public LocalRateLimiter(int defaultReplenishRate) {
        super(Config.class, CONFIGURATION_PROPERTY_NAME, (ConfigurationService) null);
        this.defaultConfig = new Config().setReplenishRate(defaultReplenishRate);
    }

    /**
     * This creates an instance with default static configuration, useful in Java DSL.
     * @param defaultReplenishRate how many tokens per second in token-bucket algorithm.
     * algorithm.
     * @param defaultRefreshPeriod the period that the tokens get replenished.
     */
    public LocalRateLimiter(int defaultReplenishRate, int defaultRefreshPeriod) {
        this(defaultReplenishRate);
        this.defaultConfig.setRefreshPeriod(defaultRefreshPeriod);
    }

    /**
     * This creates an instance with default static configuration, useful in Java DSL.
     * @param defaultReplenishRate how many tokens per second in token-bucket algorithm.
     * @param defaultRefreshPeriod the period that the tokens get replenished.
     * @param defaultRequestedTokens how many tokens are requested per request.
     */
    public LocalRateLimiter(int defaultReplenishRate, int defaultRefreshPeriod,
                            int defaultRequestedTokens) {
        this(defaultReplenishRate, defaultRefreshPeriod);
        this.defaultConfig.setRequestedTokens(defaultRequestedTokens);
    }

    public boolean isIncludeHeaders() {
        return includeHeaders;
    }

    public void setIncludeHeaders(boolean includeHeaders) {
        this.includeHeaders = includeHeaders;
    }

    public String getRemainingHeader() {
        return remainingHeader;
    }

    public void setRemainingHeader(String remainingHeader) {
        this.remainingHeader = remainingHeader;
    }

    public String getReplenishRateHeader() {
        return replenishRateHeader;
    }

    public void setReplenishRateHeader(String replenishRateHeader) {
        this.replenishRateHeader = replenishRateHeader;
    }

    public String getRefreshPeriodHeader() {
        return refreshPeriodHeader;
    }

    public void setRefreshPeriodHeader(String refreshPeriodHeader) {
        this.refreshPeriodHeader = refreshPeriodHeader;
    }

    public String getRequestedTokensHeader() {
        return requestedTokensHeader;
    }

    public void setRequestedTokensHeader(String requestedTokensHeader) {
        this.requestedTokensHeader = requestedTokensHeader;
    }

    /**
     * Used when setting default configuration in constructor.
     * @param context the ApplicationContext object to be used by this object
     * @throws BeansException if thrown by application context methods
     */
    @Override
    @SuppressWarnings("unchecked")
    public void setApplicationContext(ApplicationContext context) throws BeansException {
        if (initialized.compareAndSet(false, true)) {
            if (context.getBeanNamesForType(ConfigurationService.class).length > 0) {
                setConfigurationService(context.getBean(ConfigurationService.class));
            }
        }
    }

    private RateLimiterConfig createRateLimiterConfig(int refreshPeriod,
                                                      int replenishRate) {
        return RateLimiterConfig.custom().timeoutDuration(Duration.ofSeconds(0))
                .limitRefreshPeriod(Duration.ofSeconds(refreshPeriod))
                .limitForPeriod(replenishRate).build();
    }

    /* for testing */ Config getDefaultConfig() {
        return defaultConfig;
    }

    /**
     * This uses a basic token bucket algorithm and relies on the resilience4j-ratelimiter
     * library No other operations can run between fetching the count and writing the new
     * count.
     */
    @Override
    @SuppressWarnings("unchecked")
    public Mono<Response> isAllowed(String routeId, String id) {
        if (!this.initialized.get()) {
            throw new IllegalStateException("LocalRateLimiter is not initialized");
        }

        Config routeConfig = loadConfiguration(routeId);

        // How many requests per second do you want a user to be allowed to do?
        int replenishRate = routeConfig.getReplenishRate();

        // How many seconds for a token refresh?
        int refreshPeriod = routeConfig.getRefreshPeriod();

        // How many tokens are requested per request?
        int requestedTokens = routeConfig.getRequestedTokens();
        ////////////////////////////////////////
        //changed as original code doesnot work
        final io.github.resilience4j.ratelimiter.RateLimiter rateLimiter;
        if(!routeRateLimitMap.containsKey(id)){
            rateLimiter = RateLimiterRegistry
                    .ofDefaults()
                    .rateLimiter(id, createRateLimiterConfig(refreshPeriod, replenishRate));
            routeRateLimitMap.put(id, rateLimiter);
        }else{
            rateLimiter = routeRateLimitMap.get(id);
        }
        ////////////////////////////////////////
        /*final io.github.resilience4j.ratelimiter.RateLimiter rateLimiter = RateLimiterRegistry
                .ofDefaults()
                .rateLimiter(id, createRateLimiterConfig(refreshPeriod, replenishRate));*/

        final boolean allowed = rateLimiter.acquirePermission(requestedTokens);
        final Long tokensLeft = (long) rateLimiter.getMetrics().getAvailablePermissions();

        Response response = new Response(allowed, getHeaders(routeConfig, tokensLeft));
        if (log.isDebugEnabled()) {
            log.debug("response: " + response);
        }
        return Mono.just(response);
    }

    /* for testing */ Config loadConfiguration(String routeId) {
        Config routeConfig = getConfig().getOrDefault(routeId, defaultConfig);

        if (routeConfig == null) {
            routeConfig = getConfig().get(RouteDefinitionRouteLocator.DEFAULT_FILTERS);
        }

        if (routeConfig == null) {
            throw new IllegalArgumentException(
                    "No Configuration found for route " + routeId + " or defaultFilters");
        }
        return routeConfig;
    }

    @NotNull
    public Map<String, String> getHeaders(Config config, Long tokensLeft) {
        Map<String, String> headers = new HashMap<>();
        if (isIncludeHeaders()) {
            headers.put(this.remainingHeader, tokensLeft.toString());
            headers.put(this.refreshPeriodHeader,
                    String.valueOf(config.getRefreshPeriod()));
            headers.put(this.replenishRateHeader,
                    String.valueOf(config.getReplenishRate()));
            headers.put(this.requestedTokensHeader,
                    String.valueOf(config.getRequestedTokens()));
        }
        return headers;
    }

    @Validated
    public static class Config {

        @Min(1)
        private int replenishRate;

        @Min(1)
        private int refreshPeriod = 1;

        @Min(1)
        private int requestedTokens = 1;

        public int getReplenishRate() {
            return replenishRate;
        }

        public LocalRateLimiter.Config setReplenishRate(int replenishRate) {
            this.replenishRate = replenishRate;
            return this;
        }

        public int getRefreshPeriod() {
            return refreshPeriod;
        }

        public LocalRateLimiter.Config setRefreshPeriod(int refreshPeriod) {
            this.refreshPeriod = refreshPeriod;
            return this;
        }

        public int getRequestedTokens() {
            return requestedTokens;
        }

        public LocalRateLimiter.Config setRequestedTokens(int requestedTokens) {
            this.requestedTokens = requestedTokens;
            return this;
        }

        @Override
        public String toString() {
            return new ToStringCreator(this).append("replenishRate", replenishRate)
                    .append("refreshPeriod", refreshPeriod)
                    .append("requestedTokens", requestedTokens).toString();

        }

    }

}
