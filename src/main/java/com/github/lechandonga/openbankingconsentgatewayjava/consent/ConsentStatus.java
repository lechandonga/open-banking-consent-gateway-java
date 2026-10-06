package com.github.lechandonga.openbankingconsentgatewayjava.consent;

/**
 * 授权状态机：
 * PENDING --authorize--> AUTHORIZED --revoke--> REVOKED （终态）
 * AUTHORIZED 在有效期到达后视为过期（惰性判定，不改变持久状态）。
 */
public enum ConsentStatus {
    PENDING,
    AUTHORIZED,
    REVOKED
}
