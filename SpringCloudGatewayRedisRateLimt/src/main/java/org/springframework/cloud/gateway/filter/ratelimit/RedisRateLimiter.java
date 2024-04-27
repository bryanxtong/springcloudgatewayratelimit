package org.springframework.cloud.gateway.filter.ratelimit;

import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import jakarta.validation.constraints.Min;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.springframework.beans.BeansException;
import org.springframework.cloud.gateway.route.RouteDefinitionRouteLocator;
import org.springframework.cloud.gateway.support.ConfigurationService;
import org.springframework.context.ApplicationContext;
import org.springframework.context.ApplicationContextAware;
import org.springframework.core.style.ToStringCreator;
import org.springframework.data.redis.core.ReactiveRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.util.Assert;
import org.springframework.validation.annotation.Validated;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;
import reactor.util.function.Tuple2;

/**
 * See https://stripe.com/blog/rate-limiters and
 * https://gist.github.com/ptarjan/e38f45f2dfe601419ca3af937fff574d#file-1-check_request_rate_limiter-rb-L11-L34
 *
 * @author Spencer Gibb
 */
public class RedisRateLimiter extends AbstractRateLimiter<RedisRateLimiter.Config> implements ApplicationContextAware {
    public static final String CONFIGURATION_PROPERTY_NAME = "redis-rate-limiter";

    /**
     * Remaining Rate Limit header name.
     */
    public static final String REMAINING_HEADER = "X-RateLimit-Remaining";

    /**
     * Replenish Rate Limit header name.
     */
    public static final String REPLENISH_RATE_HEADER = "X-RateLimit-Replenish-Rate";

    /**
     * Burst Capacity header name.
     */
    public static final String BURST_CAPACITY_HEADER = "X-RateLimit-Burst-Capacity";

    /**
     * Requested Tokens header name.
     */
    public static final String REQUESTED_TOKENS_HEADER = "X-RateLimit-Requested-Tokens";

    private Log log = LogFactory.getLog(getClass());

    private ReactiveRedisTemplate<Object, Object> redisTemplate;

    private RedisScript<List<Long>> script;

    private AtomicBoolean initialized = new AtomicBoolean(false);

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

    /** The name of the header that returns the burst capacity configuration. */
    private String burstCapacityHeader = BURST_CAPACITY_HEADER;

    /** The name of the header that returns the requested tokens configuration. */
    private String requestedTokensHeader = REQUESTED_TOKENS_HEADER;

    public RedisRateLimiter(ReactiveRedisTemplate<Object, Object> redisTemplate, ConfigurationService configurationService) {
        super(Config.class, CONFIGURATION_PROPERTY_NAME, configurationService);
        this.redisTemplate = redisTemplate;
        this.initialized.compareAndSet(false, true);
    }

    /**
     * This creates an instance with default static configuration, useful in Java DSL.
     * @param defaultReplenishRate how many tokens per second in token-bucket algorithm.
     * @param defaultBurstCapacity how many tokens the bucket can hold in token-bucket
     * algorithm.
     */
    public RedisRateLimiter(int defaultReplenishRate, int defaultBurstCapacity) {
        super(Config.class, CONFIGURATION_PROPERTY_NAME, (ConfigurationService) null);
        this.defaultConfig = new Config().setReplenishRate(defaultReplenishRate).setBurstCapacity(defaultBurstCapacity);
    }

    /**
     * This creates an instance with default static configuration, useful in Java DSL.
     * @param defaultReplenishRate how many tokens per second in token-bucket algorithm.
     * @param defaultBurstCapacity how many tokens the bucket can hold in token-bucket
     * algorithm.
     * @param defaultRequestedTokens how many tokens are requested per request.
     */
    public RedisRateLimiter(int defaultReplenishRate, int defaultBurstCapacity, int defaultRequestedTokens) {
        this(defaultReplenishRate, defaultBurstCapacity);
        this.defaultConfig.setRequestedTokens(defaultRequestedTokens);
    }

    static List<String> getKeys(String id) {
        // use `{}` around keys to use Redis Key hash tags
        // this allows for using redis cluster

        // Make a unique key per user.
        String prefix = "request_rate_limiter.{" + id;

        // You need two Redis keys for Token Bucket.
        String tokenKey = prefix + "}.tokens";
        String timestampKey = prefix + "}.timestamp";
        return Arrays.asList(tokenKey, timestampKey);
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

    public String getBurstCapacityHeader() {
        return burstCapacityHeader;
    }

    public void setBurstCapacityHeader(String burstCapacityHeader) {
        this.burstCapacityHeader = burstCapacityHeader;
    }

    public String getRequestedTokensHeader() {
        return requestedTokensHeader;
    }

    public void setRequestedTokensHeader(String requestedTokensHeader) {
        this.requestedTokensHeader = requestedTokensHeader;
    }

    /**
     * This uses a basic token bucket algorithm and relies on the fact that Redis scripts
     * execute atomically. No other operations can run between fetching the count and
     * writing the new count.
     * @param id
     * @return
     */
    @Override
    // TODO: signature? params (tuple?).
    public Mono<Response> isAllowed(String routeId, String id) {
        //long replenishRate, long burstCapacity
        if (!this.initialized.get()) {
            throw new IllegalStateException("RedisRateLimiter is not initialized");
        }

        Config routeConfig = loadConfiguration(routeId);

        // How many requests per second do you want a user to be allowed to do?
        int replenishRate = routeConfig.getReplenishRate();

        // How much bursting do you want to allow?
        int burstCapacity = routeConfig.getBurstCapacity();

        // How many tokens are requested per request?
        int requestedTokens = routeConfig.getRequestedTokens();

        try {
            // Make a unique key per user.
            //List<String> keys = getKeys(id);
            String key = "request_rate_limiter." + id;

            // You need two Redis keys for Token Bucket.
            // String tokensKey = key + ".tokens";
            // String timestampKey = key + ".timestamp";

            // The arguments to the LUA script. time() returns unixtime in seconds.
            long now = Instant.now().getEpochSecond();
            int requested = 1;

            double fillTime = (double) burstCapacity / (double) replenishRate;
            int ttl = (int) Math.floor(fillTime * 2);

            return this.redisTemplate.hasKey(key).flatMap(keyExists -> {
                if (keyExists) {
                    return this.redisTemplate.opsForHash().multiGet(key,
                            Arrays.asList("tokens", "timestamp"));
                }
                return Mono.just(new ArrayList<>());
            }).publishOn(Schedulers.boundedElastic()).map(objects -> {
                Long lastTokens = null;

                if (objects.size() >= 1) {
                    lastTokens = (Long) objects.get(0);
                }
                if (lastTokens == null) {
                    lastTokens = Long.valueOf(burstCapacity);
                }

                Long lastRefreshed = null;
                if (objects.size() >= 2) {
                    lastRefreshed = (Long) objects.get(1);
                }
                if (lastRefreshed == null) {
                    lastRefreshed = 0L;
                }

                long delta = Math.max(0, (now - lastRefreshed));
                long filledTokens = Math.min(burstCapacity,
                        lastTokens + (delta * replenishRate));
                boolean allowed = filledTokens >= requested;
                long newTokens = filledTokens;
                if (allowed) {
                    newTokens = filledTokens - requested;
                }

                Map<Object, Object> updated = new HashMap<>();
                updated.put("tokens", newTokens);
                updated.put("timestamp", now);
                Mono<Boolean> putAllMono = this.redisTemplate.opsForHash().putAll(key,
                        updated);
                Mono<Boolean> expireMono = this.redisTemplate.expire(key,
                        Duration.ofSeconds(ttl));

                Flux<Tuple2<Boolean, Boolean>> zip = Flux.zip(putAllMono, expireMono);
                Tuple2<Boolean, Boolean> objects1 = zip.blockLast();
                Response response = new Response(allowed, getHeaders(routeConfig, newTokens));

                if (log.isDebugEnabled()) {
                    log.debug("response: " + response);
                }

                return response;
            });

        }
        catch (Exception e) {
            /*
             * We don't want a hard dependency on Redis to allow traffic. Make sure to set
             * an alert so you know if this is happening too much. Stripe's observed
             * failure rate is 0.01%.
             */
            log.error("Error determining if user allowed from redis", e);
        }
        return Mono.just(new Response(true, getHeaders(routeConfig, -1L)));
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
            if (this.redisTemplate == null) {
                this.redisTemplate = context.getBean(ReactiveRedisTemplate.class);
            }
            if (context.getBeanNamesForType(ConfigurationService.class).length > 0) {
                setConfigurationService(context.getBean(ConfigurationService.class));
            }
        }
    }
    /* for testing */ Config loadConfiguration(String routeId) {
        Config routeConfig = getConfig().getOrDefault(routeId, defaultConfig);

        if (routeConfig == null) {
            routeConfig = getConfig().get(RouteDefinitionRouteLocator.DEFAULT_FILTERS);
        }

        if (routeConfig == null) {
            throw new IllegalArgumentException("No Configuration found for route " + routeId + " or defaultFilters");
        }
        return routeConfig;
    }

    public Map<String, String> getHeaders(Config config, Long tokensLeft) {
        Map<String, String> headers = new HashMap<>();
        if (isIncludeHeaders()) {
            headers.put(this.remainingHeader, tokensLeft.toString());
            headers.put(this.replenishRateHeader, String.valueOf(config.getReplenishRate()));
            headers.put(this.burstCapacityHeader, String.valueOf(config.getBurstCapacity()));
            headers.put(this.requestedTokensHeader, String.valueOf(config.getRequestedTokens()));
        }
        return headers;
    }


    @Validated
    public static class Config {

        @Min(1)
        private int replenishRate;

        @Min(0)
        private int burstCapacity = 1;

        @Min(1)
        private int requestedTokens = 1;

        public int getReplenishRate() {
            return replenishRate;
        }

        public Config setReplenishRate(int replenishRate) {
            this.replenishRate = replenishRate;
            return this;
        }

        public int getBurstCapacity() {
            return burstCapacity;
        }

        public Config setBurstCapacity(int burstCapacity) {
            Assert.isTrue(burstCapacity >= this.replenishRate, "BurstCapacity(" + burstCapacity
                    + ") must be greater than or equal than replenishRate(" + this.replenishRate + ")");
            this.burstCapacity = burstCapacity;
            return this;
        }

        public int getRequestedTokens() {
            return requestedTokens;
        }

        public Config setRequestedTokens(int requestedTokens) {
            this.requestedTokens = requestedTokens;
            return this;
        }

        @Override
        public String toString() {
            return new ToStringCreator(this).append("replenishRate", replenishRate)
                    .append("burstCapacity", burstCapacity).append("requestedTokens", requestedTokens).toString();

        }

    }
}