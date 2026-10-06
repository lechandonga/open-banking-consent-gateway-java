# 开放银行接入网关（本地可运行）

基于 Spring Boot 3.4 / Java 21 的纯内存实现，不依赖外部数据库、消息中间件或真实凭据，
对外提供三大能力：**授权生命周期管理**、**请求幂等与客户配额限流**、**业务事件异步回调**。

## 1. 构建与本地验证

```bash
# 运行全部单测（22 个用例，日志含业务覆盖/用例/汇总）
mvn test

# 本地启动（默认端口 8080）
mvn spring-boot:run
```

启动后可直接联调（内置回调接收方 `POST /callbacks/receiver`，默认接收方地址指向本机自身，
无需任何外部服务即可观察投递成功）。

关键配置（`src/main/resources/application.properties`，均可覆盖）：

| 配置项 | 默认值 | 说明 |
| --- | --- | --- |
| `gateway.quota.limit-per-minute` | 10 | 每客户每分钟配额（固定窗口） |
| `gateway.consent.default-ttl-seconds` | 3600 | 授权默认有效期 |
| `gateway.callback.max-attempts` | 4 | 回调最大尝试次数（含首次） |
| `gateway.callback.backoff-base-millis` | 50 | 退避基数，第 n 次重试延迟 `base*2^(n-1)` |
| `gateway.callback.signing-secret` | local-test-secret | HMAC 签名密钥 |
| `gateway.callback.receiver-url` | http://localhost:8080/callbacks/receiver | 回调接收方 |
| `gateway.callback.timestamp-tolerance-seconds` | 300 | 接收方允许的时间戳偏差（防重放） |

## 2. 授权状态机与拒绝原因

### 状态机

```text
                 POST /{id}/authorize
   ┌─────────┐  ───────────────────────►  ┌────────────┐
   │ CREATED │                             │ AUTHORIZED │
   └─────────┘  ◄───────────────────────  └────────────┘
        │        （不允许撤销后再次生效）        │  │
        │ POST /{id}/revoke                   │  │ 到达 expiresAt
        ▼                                    │  ▼
   ┌──────────┐                              │ （派生 EXPIRED，不改持久状态）
   │ REVOKED  │ ◄────────────────────────────┘
   └──────────┘
```

- 授权绑定三元组：**客户（clientId）+ 访问范围（scopes）+ 有效期（expiresAt）**。
- `CREATED`：仅创建，尚未授权；`AUTHORIZED`：可访问；`REVOKED`：终态，不可恢复。
- 过期不落库新状态：`AUTHORIZED` 且当前时间 ≥ `expiresAt` 时按 `EXPIRED` 拒绝。

### 访问判定顺序与可区分拒绝原因

| 顺序 | 情形 | 错误码（HTTP 403） | reason |
| --- | --- | --- | --- |
| 1 | 授权不存在 | `CONSENT_NOT_FOUND` | `NOT_FOUND` |
| 2 | 未授权（CREATED） | `CONSENT_NOT_AUTHORIZED` | `NOT_AUTHORIZED` |
| 3 | 已撤销（REVOKED） | `CONSENT_REVOKED` | `REVOKED` |
| 4 | 已过期 | `CONSENT_EXPIRED` | `EXPIRED` |
| 5 | 客户不匹配或缺少所需 scope | `CONSENT_SCOPE_MISMATCH` | `SCOPE_MISMATCH` |

### 撤销与访问并发

每个授权一把内部锁，撤销在临界区内**一步**完成「状态翻转 + 版本号推进」并原子发布，
不存在只撤销一半的中间状态；访问校验在临界区内基于版本快照判定并做双重检查：
**撤销提交之后到达的任何请求一律拒绝（REVOKED），绝不会放行**。

## 3. 幂等与配额限流判定规则

### 幂等（维度：clientId + `Idempotency-Key` 请求头）

1. 网关对请求体做规范化 SHA-256 指纹；同键首次请求执行业务并缓存「请求指纹 + 状态码 + 响应体」。
2. 同键重复提交：请求指纹一致 → 直接返回**首次结果**（响应头 `Idempotent-Replay: true`），
   **业务副作用绝不重复执行**。
3. 同键但请求指纹不同 → HTTP 409 `IDEMPOTENCY_KEY_REUSED`，明确拒绝。
4. 同键并发提交：键级别锁串行化，仅一个请求成为「首个」，其余等待并复用首次结果。

### 配额（维度：clientId，固定窗口 60 秒）

- 窗口内计数达到 `limit-per-minute` 后返回 HTTP 429 `QUOTA_EXCEEDED`，响应体含
  `remaining`（剩余额度，超限时为 0）、`limit`、`resetAt`（窗口重置时间）、`retryAfterSeconds`。
- 窗口切换在客户级锁内与计数扣减同一临界区完成（惰性切换），
  **并发请求严格不超发（最多放行 limit 个），也不多扣**；切换后计数清零。

支付链路的执行顺序：**授权校验 → 幂等判定 → 配额占用 → 业务副作用（仅一次）→ 发布事件**；
任一步失败都不会产生业务副作用。

## 4. 事件回调：重试、死信与签名

- 事件由单线程守护调度器异步投递；`eventId` 全局唯一（缺省时由 `type|subjectId` 确定性派生），
  同一事件 `publishIfAbsent` **只入队一次**，重试投递携带同一 `eventId`，调度层同样去重，
  接收方可据此识别重复。
- 接收方返回非 2xx 或网络异常时按指数退避重试：
  第 1 次失败后延迟 `backoff-base-millis`，之后逐次翻倍；
  达到 `max-attempts` 后事件进入 **DEAD（死信）** 状态，保留 `attempts` 与 `lastError`，
  可通过 `GET /api/events?status=DEAD` 与 `GET /api/events/{eventId}` 查询；
  排障后可 `POST /api/events/{eventId}/requeue` 重新入队。

### 签名与重放校验

每次投递携带请求头：

| Header | 内容 |
| --- | --- |
| `X-Gateway-Event-Id` | 事件标识（同事件多次投递保持不变） |
| `X-Gateway-Nonce` | 本次投递唯一随机串（每次重试重新生成） |
| `X-Gateway-Timestamp` | 投递毫秒时间戳 |
| `X-Gateway-Signature` | HMAC-SHA256 十六进制签名 |

签名串（`SignatureUtil`）：

```text
signingBase = eventId + "\n" + nonce + "\n" + timestamp + "\n" + sha256Hex(原始请求体)
signature   = HMAC_SHA256(signingBase, gateway.callback.signing-secret) 的十六进制
```

接收方校验顺序（见内置接收方 `CallbackReceiverController`）：
时间戳偏差校验（防重放窗口）→ 重算签名比对（载荷完整性）→ nonce 去重（同 nonce 只接受一次，
重放返回 409 `nonce_replay`）→ 按 `eventId` 幂等处理重复投递。

## 5. REST API 一览

| 方法与路径 | 说明 |
| --- | --- |
| `POST /api/consents` | 创建授权（body: clientId, scopes, ttlSeconds?） |
| `POST /api/consents/{id}/authorize` | 授权生效 CREATED → AUTHORIZED |
| `POST /api/consents/{id}/revoke` | 撤销 → REVOKED |
| `GET  /api/consents/{id}` | 查询授权状态 |
| `POST /api/consents/access-check` | 访问校验联调 |
| `POST /api/payments`（头 `Idempotency-Key`） | 支付提交（授权+幂等+配额+事件全链路） |
| `GET  /api/payments/quota/{clientId}` | 查询剩余配额与重置时间 |
| `POST /api/events` | 手工发布事件（eventId 可选） |
| `GET  /api/events?status=QUEUED|DELIVERED|DEAD` | 按状态查询事件（死信含失败原因） |
| `GET  /api/events/{eventId}` | 查询单个事件 |
| `POST /api/events/{eventId}/requeue` | 死信重新入队 |
| `POST /callbacks/receiver` | 内置验签接收方（可加 `X-Simulate-Status: 500` 模拟失败） |

## 6. 快速手工验证示例

```bash
# 1) 创建并授权
CID=$(curl -s -XPOST localhost:8080/api/consents -H 'Content-Type: application/json' \
  -d '{"clientId":"c1","scopes":["payments"],"ttlSeconds":3600}' | jq -r .consentId)
curl -s -XPOST localhost:8080/api/consents/$CID/authorize

# 2) 首次支付（再执行一次同样的命令即得到幂等重放，结果一致、副作用不重复）
curl -s -XPOST localhost:8080/api/payments -H 'Content-Type: application/json' \
  -H 'Idempotency-Key: k-1' \
  -d "{\"clientId\":\"c1\",\"consentId\":\"$CID\",\"scopes\":[\"payments\"],\"paymentInformationId\":\"p1\",\"amount\":\"10.00\",\"currency\":\"GBP\"}"

# 3) 观察异步回调结果与事件状态
curl -s localhost:8080/callbacks/receiver/received | jq .
curl -s 'localhost:8080/api/events?status=DELIVERED' | jq .

# 4) 模拟接收方失败以观察重试与死信（先把 receiver-url 指向带失败头的地址或使用测试）
#    测试中由 EventDispatcherTest 覆盖：失败 4 次 -> DEAD，lastError 可查询
```

## 7. 测试与业务覆盖

`mvn` 测试日志（logger 名 `BUSINESS-TEST`）按业务模块打印覆盖情况、每个用例的开始/通过/失败与类级汇总：

- 授权业务 `ConsentServiceTest`：5 类拒绝原因 + 放行 + **撤销/访问 400 轮并发竞争**。
- 幂等业务 `IdempotencyServiceTest`：重复提交结果一致且副作用一次、同键异内容拒绝、
  **32 线程并发重复提交仅执行一次**。
- 配额业务 `QuotaServiceTest`：超限返回剩余额度/重置时间、窗口切换清零、
  **500 并发严格不超发不多扣**、客户间隔离。
- 回调业务 `SignatureUtilTest` / `EventDispatcherTest`：签名验签/篡改/重放、
  **退避重试到上限进入死信并保留原因**、先失败后成功、重复 enqueue/重复发布不重复投递。
- `IntegrationTest`：真实 HTTP 端到端串联授权拒绝、幂等、429 配额与签名回调去重。
