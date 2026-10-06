package com.github.lechandonga.openbankingconsentgatewayjava.support;

import org.junit.jupiter.api.extension.AfterAllCallback;
import org.junit.jupiter.api.extension.AfterTestExecutionCallback;
import org.junit.jupiter.api.extension.BeforeAllCallback;
import org.junit.jupiter.api.extension.BeforeEachCallback;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.atomic.AtomicInteger;

/**
 * 统一的业务测试日志扩展：
 * - 每个测试类开始时打印其覆盖的业务模块；
 * - 每个用例开始/结束打印用例名称与通过/失败情况；
 * - 类结束时汇总用例通过数，便于从日志直接看出业务覆盖与测试情况。
 */
public class BusinessLoggingExtension implements BeforeAllCallback, BeforeEachCallback,
        AfterTestExecutionCallback, AfterAllCallback {

    private static final Logger log = LoggerFactory.getLogger("BUSINESS-TEST");

    private final AtomicInteger passed = new AtomicInteger();
    private final AtomicInteger failed = new AtomicInteger();

    @Override
    public void beforeAll(ExtensionContext context) {
        String business = context.getRequiredTestClass().getSimpleName();
        log.info("══════════════════════════════════════════════════════════");
        log.info("【业务覆盖】测试类 {} —— {}", business, businessModule(context));
        log.info("══════════════════════════════════════════════════════════");
    }

    @Override
    public void beforeEach(ExtensionContext context) {
        log.info("▶ 用例开始: {}", context.getDisplayName());
    }

    @Override
    public void afterTestExecution(ExtensionContext context) {
        boolean failure = context.getExecutionException().isPresent();
        if (failure) {
            failed.incrementAndGet();
            log.error("✘ 用例失败: {} -> {}", context.getDisplayName(),
                    context.getExecutionException().get());
        } else {
            passed.incrementAndGet();
            log.info("✔ 用例通过: {}", context.getDisplayName());
        }
    }

    @Override
    public void afterAll(ExtensionContext context) {
        log.info("──────────────────────────────────────────────────────────");
        log.info("【测试汇总】{}: 通过 {} 个, 失败 {} 个",
                context.getRequiredTestClass().getSimpleName(), passed.get(), failed.get());
        log.info("──────────────────────────────────────────────────────────");
    }

    private String businessModule(ExtensionContext context) {
        String name = context.getRequiredTestClass().getName();
        if (name.contains(".consent.")) {
            return "授权创建与生命周期管理";
        }
        if (name.contains(".ratelimit.")) {
            return "请求幂等与客户配额限流";
        }
        if (name.contains(".event.")) {
            return "业务事件异步回调/重试/死信";
        }
        if (name.contains("Integration")) {
            return "端到端网关集成";
        }
        return "开放银行接入网关";
    }
}
