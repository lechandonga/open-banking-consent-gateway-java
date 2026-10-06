package com.github.lechandonga.openbankingconsentgatewayjava.ratelimit;

import java.time.Instant;

public record QuotaTicket(String clientId, int remaining, int limit, Instant resetAt) {
}
