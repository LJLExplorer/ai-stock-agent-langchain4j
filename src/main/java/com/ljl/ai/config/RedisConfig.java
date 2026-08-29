package com.ljl.ai.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.data.redis.RedisProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.connection.RedisPassword;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;

@Slf4j
@Configuration
@EnableConfigurationProperties(RedisProperties.class)
public class RedisConfig {

    @Bean
    public RedisConnectionFactory redisConnectionFactory(RedisProperties properties) {
        RedisStandaloneConfiguration configuration = new RedisStandaloneConfiguration(
                properties.getHost(), properties.getPort());
        configuration.setDatabase(properties.getDatabase());

        if (properties.getUsername() != null && !properties.getUsername().isBlank()) {
            configuration.setUsername(properties.getUsername());
            configuration.setPassword(RedisPassword.of(properties.getPassword()));
        } else if (properties.getPassword() != null && !properties.getPassword().isBlank()) {
            configuration.setPassword(RedisPassword.of(properties.getPassword()));
        }

        log.info("初始化Redis连接, host: {}, port: {}, database: {}, passwordConfigured: {}",
                properties.getHost(), properties.getPort(), properties.getDatabase(),
                properties.getPassword() != null && !properties.getPassword().isBlank());
        return new LettuceConnectionFactory(configuration);
    }
}
