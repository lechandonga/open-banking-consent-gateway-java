package com.github.lechandonga.openbankingconsentgatewayjava.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "gateway")
public class GatewayProperties {

    private final Quota quota = new Quota();
    private final Consent consent = new Consent();
    private final Callback callback = new Callback();

    public Quota getQuota() { return quota; }
    public Consent getConsent() { return consent; }
    public Callback getCallback() { return callback; }

    public static class Quota {
        private int limitPerMinute = 10;
        public int getLimitPerMinute() { return limitPerMinute; }
        public void setLimitPerMinute(int v) { this.limitPerMinute = v; }
    }

    public static class Consent {
        private long defaultTtlSeconds = 3600;
        public long getDefaultTtlSeconds() { return defaultTtlSeconds; }
        public void setDefaultTtlSeconds(long v) { this.defaultTtlSeconds = v; }
    }

    public static class Callback {
        private String receiverUrl = "http://localhost:8080/callbacks/receiver";
        private int maxAttempts = 4;
        private long backoffBaseMillis = 50;
        private String signingSecret = "local-test-secret";
        private long timestampToleranceSeconds = 300;
        private int nonceCacheSize = 1024;
        public String getReceiverUrl() { return receiverUrl; }
        public void setReceiverUrl(String v) { this.receiverUrl = v; }
        public int getMaxAttempts() { return maxAttempts; }
        public void setMaxAttempts(int v) { this.maxAttempts = v; }
        public long getBackoffBaseMillis() { return backoffBaseMillis; }
        public void setBackoffBaseMillis(long v) { this.backoffBaseMillis = v; }
        public String getSigningSecret() { return signingSecret; }
        public void setSigningSecret(String v) { this.signingSecret = v; }
        public long getTimestampToleranceSeconds() { return timestampToleranceSeconds; }
        public void setTimestampToleranceSeconds(long v) { this.timestampToleranceSeconds = v; }
        public int getNonceCacheSize() { return nonceCacheSize; }
        public void setNonceCacheSize(int v) { this.nonceCacheSize = v; }
    }
}
