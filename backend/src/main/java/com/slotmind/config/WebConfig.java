package com.slotmind.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

@Configuration
public class WebConfig implements WebMvcConfigurer {
    private final String[] origins;

    public WebConfig(@Value("${slotmind.cors.allowed-origins:http://localhost:5173}") String[] origins) { this.origins = origins; }

    @Override
    public void addCorsMappings(CorsRegistry registry) {
        registry.addMapping("/**").allowedOrigins(origins)
                .allowedMethods("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS").allowedHeaders("*");
    }

    /** Dedicated pool for SSE streaming so request threads are released immediately. */
    @Bean(destroyMethod = "shutdownNow")
    public ExecutorService sseExecutor() { return Executors.newFixedThreadPool(4); }
}
