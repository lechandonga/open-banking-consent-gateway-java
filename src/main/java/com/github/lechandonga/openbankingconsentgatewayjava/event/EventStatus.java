package com.github.lechandonga.openbankingconsentgatewayjava.event;

public enum EventStatus {
    /** 已创建，等待投递 */
    PENDING,
    /** 重试中 */
    RETRYING,
    /** 已被接收方确认 */
    DELIVERED,
    /** 超过最大尝试次数，进入死信 */
    DEAD
}
