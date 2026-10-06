package com.github.lechandonga.openbankingconsentgatewayjava.common;

/**
 * 可区分的访问拒绝原因。
 */
public enum DenyReason {
    /** 授权不存在或尚未授权 */
    UNAUTHORIZED,
    /** 授权已被客户撤销 */
    REVOKED,
    /** 授权超过有效期 */
    EXPIRED,
    /** 请求的访问范围未被该授权覆盖 */
    SCOPE_MISMATCH
}
