package com.github.lechandonga.openbankingconsentgatewayjava.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "gateway")
public class GatewayProperties {

    private final Quota quota = new Quota();
    private final Callback callback = new Callback();

    public Quota getQuota() {
        return quota;
    }

    public Callback getCallback() {
        return callback;
    }

    public static class Quota {
        private int limit = 10;
        private long windowMs = 60_000;

        public int getLimit() {
            return limit;
        }

        public void setLimit(int limit) {
            this.limit = limit;
        }

        public long getWindowMs() {
            return windowMs;
        }

        public void setWindowMs(long windowMs) {
            this.windowMs = windowMs;
        }
    }

    public static class Callback {
        private int maxAttempts = 5;
        private long backoffBaseMs = 200;
        private long backoffMaxMs = 5_000;
        private String signingSecret = "local-demo-signing-secret";
        private String targetUrl;
        private long maxTimestampSkewSeconds = 300;
        private int workerThreads = 2;

        public int getMaxAttempts() {
            return maxAttempts;
        }

        public void setMaxAttempts(int maxAttempts) {
            this.maxAttempts = maxAttempts;
        }

        public long getBackoffBaseMs() {
            return backoffBaseMs;
        }

        public void setBackoffBaseMs(long backoffBaseMs) {
            this.backoffBaseMs = backoffBaseMs;
        }

        public long getBackoffMaxMs() {
            return backoffMaxMs;
        }

        public void setBackoffMaxMs(long backoffMaxMs) {
            this.backoffMaxMs = backoffMaxMs;
        }

        public String getSigningSecret() {
            return signingSecret;
        }

        public void setSigningSecret(String signingSecret) {
            this.signingSecret = signingSecret;
        }

        public String getTargetUrl() {
            return targetUrl;
        }

        public void setTargetUrl(String targetUrl) {
            this.targetUrl = targetUrl;
        }

        public long getMaxTimestampSkewSeconds() {
            return maxTimestampSkewSeconds;
        }

        public void setMaxTimestampSkewSeconds(long maxTimestampSkewSeconds) {
            this.maxTimestampSkewSeconds = maxTimestampSkewSeconds;
        }

        public int getWorkerThreads() {
            return workerThreads;
        }

        public void setWorkerThreads(int workerThreads) {
            this.workerThreads = workerThreads;
        }
    }
}
