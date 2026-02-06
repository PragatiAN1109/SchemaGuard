package com.schemaguard.config;


import com.schemaguard.store.InMemoryKeyValueStore;
import com.schemaguard.store.KeyValueStore;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class AppConfig {

    @Bean
    public KeyValueStore keyValueStore() {
        return new InMemoryKeyValueStore();
    }
}