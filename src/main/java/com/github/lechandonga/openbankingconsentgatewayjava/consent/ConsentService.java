package com.github.lechandonga.openbankingconsentgatewayjava.consent;

import com.github.lechandonga.openbankingconsentgatewayjava.config.GatewayProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 授权生命周期管理（内存实现）。
 *
 * 并发模型：每个授权一把锁（StampedLock 的写锁互斥撤销与状态变更，
 * 访问校验在读锁内基于版本快照完成）。撤销一旦提交（状态+版本原子发布），
 * 之后的所有访问都只能看到 REVOKED，绝不放行；撤销本身在临界区内一步完成，
 * 不存在只撤销一半的中间状态。
 */
@Service
public class ConsentService {

    private static final Logger log = LoggerFactory.getLogger(ConsentService.class);

    private final Map<String, Consent> consents = new ConcurrentHashMap<>();
    private final Map<String, Object> locks = new ConcurrentHashMap<>();
    private final GatewayProperties properties;
    private final Clock clock;

    @Autowired
    public ConsentService(GatewayProperties properties) {
        this(properties, Clock.systemUTC());
    }

    ConsentService(GatewayProperties properties, Clock clock) {
        this.properties = properties;
        this.clock = clock;
    }

    private Object lockFor(String consentId) {
        return locks.computeIfAbsent(consentId, k -> new Object());
    }

    /**
     * 创建授权（CREATED，尚未生效）。
     */
    public Consent createConsent(String clientId, Set<String> scopes, Long ttlSeconds) {
        long ttl = ttlSeconds != null ? ttlSeconds : properties.getConsent().getDefaultTtlSeconds();
        String id = UUID.randomUUID().toString();
        Instant expiresAt = clock.instant().plusSeconds(ttl);
        Consent consent = new Consent(id, clientId, scopes, expiresAt, ConsentStatus.CREATED, 0);
        consents.put(id, consent);
        log.info("授权创建 consentId={} clientId={} scopes={} expiresAt={}", id, clientId, scopes, expiresAt);
        return consent;
    }

    /**
     * 客户完成授权确认，CREATED -> AUTHORIZED。
     */
    public Consent authorize(String consentId) {
        synchronized (lockFor(consentId)) {
            Consent consent = require(consentId);
            if (consent.getStatus() == ConsentStatus.REVOKED) {
                throw new ConsentAccessDeniedException(ConsentDenyReason.REVOKED, consentId,
                        "授权已撤销，无法再次生效");
            }
            if (!clock.instant().isBefore(consent.getExpiresAt())) {
                throw new ConsentAccessDeniedException(ConsentDenyReason.EXPIRED, consentId,
                        "授权已过期，无法生效");
            }
            consent.setStatus(ConsentStatus.AUTHORIZED);
            consent.setVersion(consent.getVersion() + 1);
            log.info("授权生效 consentId={} version={}", consentId, consent.getVersion());
            return consent;
        }
    }

    /**
     * 撤销授权：临界区内一步完成状态翻转与版本推进，原子发布。
     */
    public Consent revoke(String consentId) {
        synchronized (lockFor(consentId)) {
            Consent consent = require(consentId);
            consent.setStatus(ConsentStatus.REVOKED);
            consent.setVersion(consent.getVersion() + 1);
            log.info("授权撤销 consentId={} version={}", consentId, consent.getVersion());
            return consent;
        }
    }

    public Consent get(String consentId) {
        return require(consentId);
    }

    /**
     * 访问校验：返回通过的授权快照，否则抛出带可区分原因的异常。
     * 校验顺序：不存在 -> 未授权 -> 已撤销 -> 已过期 -> 范围不匹配。
     * 撤销与访问并发时，要么整个校验发生在撤销提交之前（放行），
     * 要么发生在之后（拒绝），撤销之后一律不得放行。
     */
    public Consent checkAccess(String consentId, String clientId, Set<String> requiredScopes) {
        Consent consent = consents.get(consentId);
        if (consent == null) {
            throw new ConsentAccessDeniedException(ConsentDenyReason.NOT_FOUND, consentId,
                    "授权不存在: " + consentId);
        }
        synchronized (lockFor(consentId)) {
            long version = consent.getVersion();
            ConsentStatus status = consent.getStatus();
            Instant expiresAt = consent.getExpiresAt();
            Instant now = clock.instant();

            if (status != ConsentStatus.AUTHORIZED) {
                if (status == ConsentStatus.REVOKED) {
                    throw new ConsentAccessDeniedException(ConsentDenyReason.REVOKED, consentId,
                            "授权已撤销: " + consentId);
                }
                throw new ConsentAccessDeniedException(ConsentDenyReason.NOT_AUTHORIZED, consentId,
                        "授权尚未生效: " + consentId);
            }
            if (!now.isBefore(expiresAt)) {
                throw new ConsentAccessDeniedException(ConsentDenyReason.EXPIRED, consentId,
                        "授权已过期: " + consentId);
            }
            if (!consent.getClientId().equals(clientId) || !consent.getScopes().containsAll(requiredScopes)) {
                throw new ConsentAccessDeniedException(ConsentDenyReason.SCOPE_MISMATCH, consentId,
                        "客户或访问范围不匹配: " + consentId);
            }
            // 双重检查：校验期间撤销可能刚好提交；版本变化说明发生过状态变更，必须重判
            if (consent.getVersion() != version || consent.getStatus() != ConsentStatus.AUTHORIZED) {
                throw new ConsentAccessDeniedException(ConsentDenyReason.REVOKED, consentId,
                        "授权在访问期间被撤销: " + consentId);
            }
            log.debug("访问放行 consentId={} clientId={} scopes={}", consentId, clientId, requiredScopes);
            return new Consent(consent.getId(), consent.getClientId(), consent.getScopes(),
                    consent.getExpiresAt(), consent.getStatus(), consent.getVersion());
        }
    }

    private Consent require(String consentId) {
        Consent consent = consents.get(consentId);
        if (consent == null) {
            throw new ConsentAccessDeniedException(ConsentDenyReason.NOT_FOUND, consentId,
                    "授权不存在: " + consentId);
        }
        return consent;
    }

    /** 仅供测试/重置使用 */
    Map<String, Consent> snapshot() {
        return new HashMap<>(consents);
    }
}
