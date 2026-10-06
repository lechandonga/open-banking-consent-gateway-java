package com.github.lechandonga.openbankingconsentgatewayjava.tests;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 测试日志工具：统一打印业务域、用例与断言结果，方便从 surefire 日志查看覆盖情况。
 */
public final class TestLog {

    private static final Logger LOG = LoggerFactory.getLogger("BUSINESS-TESTS");

    private TestLog() {
    }

    public static void business(String business, String description) {
        LOG.info("");
        LOG.info("================ 业务域: {} | {} ================", business, description);
    }

    public static void caseNo(String business, int no, String name) {
        LOG.info("[{}] 用例{}: {}", business, no, name);
    }

    public static void pass(String check) {
        LOG.info("    断言通过: {}", check);
    }

    public static void info(String message) {
        LOG.info("    {}", message);
    }
}
