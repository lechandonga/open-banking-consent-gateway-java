package com.github.lechandonga.openbankingconsentgatewayjava.event;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

/**
 * 基于 JDK HttpClient 的生产传输。请求头携带：
 * X-Gateway-Event-Id（事件标识）、X-Gateway-Nonce、X-Gateway-Timestamp、X-Gateway-Signature。
 */
@Component
public class HttpClientCallbackTransport implements CallbackTransport {

    private static final Logger log = LoggerFactory.getLogger(HttpClientCallbackTransport.class);

    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(2))
            .build();

    @Override
    public boolean deliver(String callbackUrl, Event event, String body, String signature,
                           String nonce, long timestamp) {
        try {
            HttpRequest request = HttpRequest.newBuilder(URI.create(callbackUrl))
                    .timeout(Duration.ofSeconds(3))
                    .header("Content-Type", "application/json")
                    .header("X-Gateway-Event-Id", event.getEventId())
                    .header("X-Gateway-Nonce", nonce)
                    .header("X-Gateway-Timestamp", String.valueOf(timestamp))
                    .header("X-Gateway-Signature", signature)
                    .POST(HttpRequest.BodyPublishers.ofString(body))
                    .build();
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            boolean ok = response.statusCode() >= 200 && response.statusCode() < 300;
            if (!ok) {
                log.warn("回调投递非 2xx eventId={} status={}", event.getEventId(), response.statusCode());
            }
            return ok;
        } catch (Exception e) {
            log.warn("回调投递异常 eventId={} error={}", event.getEventId(), e.toString());
            return false;
        }
    }
}
