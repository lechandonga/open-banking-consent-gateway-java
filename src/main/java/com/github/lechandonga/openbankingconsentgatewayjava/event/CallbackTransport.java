package com.github.lechandonga.openbankingconsentgatewayjava.event;

/**
 * 回调投递传输抽象。生产实现走 JDK HttpClient，测试中可注入可控的桩实现。
 */
public interface CallbackTransport {

    /**
     * @return true 表示接收方返回 2xx；false/异常表示投递失败，需要重试
     */
    boolean deliver(String callbackUrl, Event event, String body, String signature,
                    String nonce, long timestamp);
}
