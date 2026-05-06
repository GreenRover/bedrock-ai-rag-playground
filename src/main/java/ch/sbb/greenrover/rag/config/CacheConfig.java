package ch.sbb.greenrover.rag.config;

import com.github.benmanes.caffeine.cache.Caffeine;
import org.springframework.cache.CacheManager;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.cache.caffeine.CaffeineCacheManager;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.concurrent.TimeUnit;

@Configuration
@EnableCaching
public class CacheConfig {

    @Bean
    public CacheManager cacheManager() {
        CaffeineCacheManager cacheManager = new CaffeineCacheManager("confluenceUsers", "confluenceAttachments");
        cacheManager.setCaffeine(Caffeine.newBuilder()
                .expireAfterWrite(12, TimeUnit.HOURS) // Cache valid for 12 hours
                .maximumSize(1000)); // Maximum 1000 items per cache
        return cacheManager;
    }
}
