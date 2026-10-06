package com.github.lechandonga.openbankingconsentgatewayjava.event;

/**
 * 回调投递通道抽象，便于在测试中替换为故障注入实现。
 */
public interface CallbackTransport {

    /**
     * 投递一次回调。成功（2xx）正常返回，失败抛出异常并携带失败原因。
     */
    void deliver(BusinessEvent event, String body, long timestamp, String signature) throws Exception;
}
