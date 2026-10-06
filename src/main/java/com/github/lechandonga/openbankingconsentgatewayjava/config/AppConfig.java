package com.github.lechandonga.openbankingconsentgatewayjava.config;

import java.time.Clock;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.lechandonga.openbankingconsentgatewayjava.event.CallbackTransport;
import com.github.lechandonga.openbankingconsentgatewayjava.event.EventDispatcher;
import com.github.lechandonga.openbankingconsentgatewayjava.event.EventStore;
import com.github.lechandonga.openbankingconsentgatewayjava.event.HttpCallbackTransport;

@Configuration
@EnableConfigurationProperties(GatewayProperties.class)
public class AppConfig {

    @Bean
    public Clock clock() {
        return Clock.systemUTC();
    }

    @Bean
    public CallbackTransport callbackTransport(GatewayProperties properties) {
        return new HttpCallbackTransport(properties);
    }

    @Bean
    public EventDispatcher eventDispatcher(EventStore store, GatewayProperties properties,
                                           CallbackTransport transport, ObjectMapper objectMapper) {
        return new EventDispatcher(store, properties, transport, objectMapper);
    }
}
