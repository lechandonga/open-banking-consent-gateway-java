package com.github.lechandonga.openbankingconsentgatewayjava.consent;

import java.time.Clock;
import java.time.Instant;
import java.util.Set;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

@Service
public class ConsentService {

    private final ConsentStore store;
    private final Clock clock;

    @Autowired
    public ConsentService(ConsentStore store, Clock clock) {
        this.store = store;
        this.clock = clock;
    }

    public ConsentService(Clock creationClock, Clock accessClock) {
        this.store = new ConsentStore(accessClock);
        this.clock = creationClock;
    }

    public Consent create(String customerId, Set<String> scopes, long ttlSeconds) {
        if (customerId == null || customerId.isBlank()) {
            throw new IllegalArgumentException("customerId is required");
        }
        if (scopes == null || scopes.isEmpty()) {
            throw new IllegalArgumentException("at least one scope is required");
        }
        if (ttlSeconds <= 0) {
            throw new IllegalArgumentException("ttlSeconds must be positive");
        }
        return store.create(customerId, scopes, clock.instant().plusSeconds(ttlSeconds));
    }

    public Consent authorize(String id) {
        return store.transition(id, ConsentStatus.AUTHORIZED);
    }

    public Consent revoke(String id) {
        return store.transition(id, ConsentStatus.REVOKED);
    }

    public Consent get(String id) {
        return store.find(id).orElseThrow(() -> new IllegalArgumentException("consent not found: " + id));
    }

    public Consent checkAccess(String id, String scope) {
        return store.authorizeAccess(id, scope);
    }
}
