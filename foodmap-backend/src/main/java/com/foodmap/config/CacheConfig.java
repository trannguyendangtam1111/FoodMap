package com.foodmap.config;

import com.github.benmanes.caffeine.cache.Caffeine;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.cache.CacheManager;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.cache.caffeine.CaffeineCache;
import org.springframework.cache.support.SimpleCacheManager;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.List;
import java.util.concurrent.TimeUnit;

@Configuration
@EnableCaching
public class CacheConfig {

    public static final String TRENDING_FOODS = "trendingFoods";
    public static final String TRENDING_RESTAURANTS = "trendingRestaurants";
    public static final String RESTAURANT_LIST = "restaurantList";

    @Value("${app.cache.trending-ttl-minutes:30}")
    private int trendingTtlMinutes;

    @Value("${app.cache.restaurant-list-ttl-minutes:5}")
    private int restaurantListTtlMinutes;

    @Bean
    public CacheManager cacheManager() {
        SimpleCacheManager manager = new SimpleCacheManager();
        manager.setCaches(List.of(
            buildCache(TRENDING_FOODS, trendingTtlMinutes, 200),
            buildCache(TRENDING_RESTAURANTS, trendingTtlMinutes, 200),
            buildCache(RESTAURANT_LIST, restaurantListTtlMinutes, 500)
        ));
        return manager;
    }

    private CaffeineCache buildCache(String name, int ttlMinutes, int maxSize) {
        return new CaffeineCache(name,
            Caffeine.newBuilder()
                .expireAfterWrite(ttlMinutes, TimeUnit.MINUTES)
                .maximumSize(maxSize)
                .recordStats()
                .build());
    }
}
