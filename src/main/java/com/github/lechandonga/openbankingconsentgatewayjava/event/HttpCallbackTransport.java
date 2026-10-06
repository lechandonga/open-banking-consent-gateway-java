package com.github.lechandonga.openbankingconsentgatewayjava.event;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

import com.github.lechandonga.openbankingconsentgatewayjava.config.GatewayProperties;

/**
 * 基于 JDK HttpClient 的回调投递，无第三方依赖。
 */
public class HttpCallbackTransport implements CallbackTransport {

    private final GatewayProperties properties;
    private final HttpClient httpClient;

    public HttpCallbackTransport(GatewayProperties properties) {
        this.properties = properties;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(2))
                .build();
    }

    @Override
    public void deliver(BusinessEvent event, String body, long timestamp, String signature) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create(properties.getCallback().getTargetUrl()))
                .timeout(Duration.ofSeconds(3))
                .header("Content-Type", "application/json")
                .header("X-Event-Id", event.getEventId())
                .header("X-Timestamp", Long.toString(timestamp))
                .header("X-Signature", signature)
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();
        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        int status = response.statusCode();
        if (status < 200 || status >= 300) {
            throw new IllegalStateException("receiver responded HTTP " + status + ": " + response.body());
        }
    }
}
