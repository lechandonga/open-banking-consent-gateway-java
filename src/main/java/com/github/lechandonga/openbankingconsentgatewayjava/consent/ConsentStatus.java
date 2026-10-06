package com.github.lechandonga.openbankingconsentgatewayjava.consent;

/**
 * 授权状态机：CREATED -> AUTHORIZED -> REVOKED；AUTHORIZED 到期后按 EXPIRED 处理。
 */
public enum ConsentStatus {
    CREATED,
    AUTHORIZED,
    REVOKED
}
