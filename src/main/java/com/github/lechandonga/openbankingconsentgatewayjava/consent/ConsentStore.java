package com.github.lechandonga.openbankingconsentgatewayjava.consent;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import java.time.Clock;

import org.springframework.stereotype.Repository;

import com.github.lechandonga.openbankingconsentgatewayjava.common.AccessDeniedException;
import com.github.lechandonga.openbankingconsentgatewayjava.common.DenyReason;

/**
 * 授权的并发安全内存存储。
 *
 * 每条授权用一把锁串行化其生命周期变更与访问校验，保证“撤销与访问竞争”时
 * 撤销是原子的：要么请求在撤销前完整放行，要么在撤销后被拒绝，不存在半撤销状态。
 */
@Repository
public class ConsentStore {

    private final ConcurrentHashMap<String, Consent> consents = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Object> locks = new ConcurrentHashMap<>();
    private final Clock clock;

    public ConsentStore(Clock clock) {
        this.clock = clock;
    }

    public Consent create(String customerId, Set<String> scopes, Instant expiresAt) {
        String id = "cns-" + Long.toHexString(System.nanoTime()) + "-" + Integer.toHexString(System.identityHashCode(scopes));
        Consent consent = new Consent(id, customerId, scopes, expiresAt);
        consents.put(id, consent);
        locks.put(id, new Object());
        return consent;
    }

    public Optional<Consent> find(String id) {
        return Optional.ofNullable(consents.get(id));
    }

    public List<Consent> findByCustomer(String customerId) {
        return consents.values().stream().filter(c -> c.getCustomerId().equals(customerId)).toList();
    }

    /**
     * 在授权锁内执行状态迁移。已处于目标状态时幂等返回。
     */
    public Consent transition(String id, ConsentStatus target) {
        Consent consent = consents.get(id);
        if (consent == null) {
            throw new IllegalArgumentException("consent not found: " + id);
        }
        synchronized (locks.get(id)) {
            if (consent.getStatus() == target) {
                return consent;
            }
            if (consent.getStatus() == ConsentStatus.REVOKED) {
                throw new IllegalStateException("consent already revoked: " + id);
            }
            if (target == ConsentStatus.AUTHORIZED) {
                consent.markAuthorized();
            } else if (target == ConsentStatus.REVOKED) {
                consent.markRevoked();
            } else {
                throw new IllegalStateException("cannot move to " + target);
            }
            return consent;
        }
    }

    /**
     * 访问鉴权：与撤销共用同一把锁，撤销完成后的请求一律不得放行。
     *
     * 拒绝原因判定顺序：未授权（含不存在/PENDING） -> 已撤销 -> 已过期 -> 范围不匹配。
     */
    public Consent authorizeAccess(String id, String requiredScope) {
        Consent consent = consents.get(id);
        if (consent == null) {
            throw new AccessDeniedException(DenyReason.UNAUTHORIZED, "consent not found: " + id);
        }
        synchronized (locks.get(id)) {
            if (consent.getStatus() == ConsentStatus.PENDING) {
                throw new AccessDeniedException(DenyReason.UNAUTHORIZED, "consent not authorized yet: " + id);
            }
            if (consent.getStatus() == ConsentStatus.REVOKED) {
                throw new AccessDeniedException(DenyReason.REVOKED, "consent revoked: " + id);
            }
            if (consent.isExpired(clock.instant())) {
                throw new AccessDeniedException(DenyReason.EXPIRED, "consent expired at " + consent.getExpiresAt());
            }
            if (!consent.getScopes().contains(requiredScope)) {
                throw new AccessDeniedException(DenyReason.SCOPE_MISMATCH,
                        "scope '" + requiredScope + "' not granted, allowed=" + consent.getScopes());
            }
            return consent;
        }
    }
}
