package com.github.lechandonga.openbankingconsentgatewayjava.consent;

/**
 * 可区分的访问拒绝原因。
 */
public enum ConsentDenyReason {
    NOT_FOUND,
    NOT_AUTHORIZED,
    REVOKED,
    EXPIRED,
    SCOPE_MISMATCH
}
