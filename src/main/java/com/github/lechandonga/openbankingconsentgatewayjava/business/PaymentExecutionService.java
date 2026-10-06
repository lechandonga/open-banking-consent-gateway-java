package com.github.lechandonga.openbankingconsentgatewayjava.business;

import com.github.lechandonga.openbankingconsentgatewayjava.business.dto.PaymentRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 模拟开放银行业务（国内支付提交）。executionCount 用于在测试中证明：
 * 幂等重放绝不会重复执行业务副作用。
 */
@Service
public class PaymentExecutionService {

    private static final Logger log = LoggerFactory.getLogger(PaymentExecutionService.class);

    private final AtomicInteger executionCount = new AtomicInteger();
    private final Map<String, String> payments = new ConcurrentHashMap<>();

    public Map<String, Object> submit(PaymentRequest request) {
        int n = executionCount.incrementAndGet();
        String paymentId = request.getPaymentInformationId() != null
                ? request.getPaymentInformationId() : UUID.randomUUID().toString();
        payments.put(paymentId, "ACSP");
        log.info("业务副作用执行 paymentId={} amount={} {} 第 {} 次执行",
                paymentId, request.getAmount(), request.getCurrency(), n);
        return Map.of(
                "paymentId", paymentId,
                "status", "ACSP",
                "amount", String.valueOf(request.getAmount()),
                "currency", String.valueOf(request.getCurrency()),
                "executionNo", n);
    }

    public int getExecutionCount() { return executionCount.get(); }
}
