# 开放银行接入网关（本地可运行版）

基于 Spring Boot 3.4 / Java 21 实现的开放银行接入网关参考实现，**不依赖任何外部服务、数据库或真实凭据**：
所有状态保存在内存中，回调接收方由本应用内置，可直接在本地启动、联调与测试。

提供三大能力：

1. **授权管理**：授权的创建、授权生效、撤销与访问鉴权，绑定客户、访问范围（scope）与有效期。
2. **幂等与配额**：按客户维度的请求幂等回放与固定窗口配额限流。
3. **事件回调**：业务事件异步投递、指数退避重试、死信查询与 HMAC 签名。

## 本地构建与启动

```bash
mvn spring-boot:run
# 或
mvn package
java -jar target/open-banking-consent-gateway-java-1.0-SNAPSHOT.jar
```

默认端口 `8080`；若被占用可指定端口（注意同时覆盖回调地址）：

```bash
java -jar target/open-banking-consent-gateway-java-1.0-SNAPSHOT.jar \
  --server.port=18080 \
  --gateway.callback.target-url=http://localhost:18080/internal/receiver
```

## 配置项（src/main/resources/application.properties）

| 配置 | 默认值 | 说明 |
| --- | --- | --- |
| `gateway.quota.limit` | 10 | 每客户每窗口最大请求数 |
| `gateway.quota.window-ms` | 60000 | 固定窗口长度（毫秒） |
| `gateway.callback.max-attempts` | 5 | 最大投递尝试次数（含首次） |
| `gateway.callback.backoff-base-ms` | 200 | 退避基数；第 n 次失败后等待 `base*2^(n-1)` |
| `gateway.callback.backoff-max-ms` | 5000 | 退避上限（毫秒） |
| `gateway.callback.signing-secret` | local-demo-signing-secret | HMAC 签名密钥（演示值，生产应由密钥管理注入） |
| `gateway.callback.target-url` | 内置接收方 | 回调目标地址 |
| `gateway.callback.max-timestamp-skew-seconds` | 300 | 接收方允许的时间戳偏移，超出视为重放 |

## 一、授权状态机与拒绝原因

### 状态机

```
PENDING ──authorize──▶ AUTHORIZED ──revoke──▶ REVOKED（终态）
                            │
                            └─ 到达 expiresAt 后视为 EXPIRED（惰性判定，不改持久状态）
```

- 创建后为 `PENDING`；`POST /api/consents/{id}/authorize` 后为 `AUTHORIZED`。
- `revoke` 是原子操作：在每授权锁内一次完成，**不存在“只撤销一半”的中间状态**；重复撤销幂等。
- `REVOKED` 为终态，不可重新授权。
- 过期不做后台扫描，在访问鉴权时惰性判定。

### 访问鉴权与可区分拒绝原因

`POST /api/consents/{id}/access?scope=xxx`，在**与撤销相同的锁内**判定，顺序为：

| 顺序 | 条件 | HTTP | `code` |
| --- | --- | --- | --- |
| 1 | 授权不存在 / `PENDING` | 403 | `UNAUTHORIZED` |
| 2 | 已撤销（或撤销先于访问完成） | 403 | `REVOKED` |
| 3 | 当前时间 ≥ `expiresAt` | 403 | `EXPIRED` |
| 4 | 授权 scope 集合不包含请求 scope | 403 | `SCOPE_MISMATCH` |

**撤销/访问并发语义**：每个授权独立加锁，撤销与访问互斥。因此撤销完成后的请求一律被拒绝；
在撤销完成前进入临界区的请求要么完整放行，要么随撤销完成被拒绝，二者皆为确定结局。

### 接口

```bash
# 创建（PENDING，绑定客户、scope、有效期秒数）
curl -X POST localhost:8080/api/consents -H 'Content-Type: application/json' \
  -d '{"customerId":"c1","scopes":["accounts.read"],"ttlSeconds":3600}'
# 授权生效 / 撤销 / 查询
curl -X POST localhost:8080/api/consents/{id}/authorize
curl -X POST localhost:8080/api/consents/{id}/revoke
curl       localhost:8080/api/consents/{id}
# 鉴权访问
curl -X POST 'localhost:8080/api/consents/{id}/access?scope=accounts.read'
```

## 二、幂等与限流判定规则

### 幂等

`POST /api/gateway/customers/{customerId}/requests`，必须携带 `Idempotency-Key` 请求头。
幂等键的作用域为**单个客户**（内部组合键 `customerId:key`）。请求体以 SHA-256 摘要比对：

1. 键不存在：先占配额，再执行业务，结果（含状态码与响应体）在键锁内原子落库，返回 **201**；
2. 键存在且摘要一致：直接回放首次结果，返回 **200** 且 `replay=true`，
   **不扣配额、不重复执行业务副作用**（并发重复提交在同一锁上串行化，同样保证只执行一次）；
3. 键存在但摘要不同：返回 **409** `IDEMPOTENCY_KEY_CONFLICT`，不执行业务、不扣配额。

### 配额限流

- 固定窗口算法，按客户计数。窗口起点为 `floor(now / windowMs) * windowMs`，窗口切换在客户锁内原子重置。
- 新业务请求先 `acquire` 配额再执行：额度不足返回 **429** `QUOTA_EXCEEDED`，响应包含
  `limit`、`remaining`（超额时为 0）与 `resetAt`（窗口重置的 epoch 毫秒）。
- 并发请求在每客户锁内串行取号，**不超发也不多扣**；幂等回放不消耗额度。

```bash
curl -X POST localhost:8080/api/gateway/customers/c1/requests \
  -H 'Idempotency-Key: 7e2...' -d '{"paymentId":"p-1","amount":100}'
curl localhost:8080/api/gateway/customers/c1/quota
# {"customerId":"c1","limit":10,"used":1,"remaining":9,"windowStart":...,"resetAt":...}
```

## 三、事件回调、重试与签名

### 发布与查询

```bash
curl -X POST localhost:8080/api/events -H 'Content-Type: application/json' \
  -d '{"eventId":"evt-1","type":"payment.completed","customerId":"c1","payload":{"paymentId":"p-1"}}'
curl localhost:8080/api/events/evt-1          # 状态/尝试次数/下次重试时间/最后失败原因
curl localhost:8080/api/events/dead-letters   # 全部死信事件
```

- `eventId` 相同的重复发布**只入队一次**（入库为原子 putIfAbsent，后续发布直接忽略）。
- 事件状态：`PENDING -> RETRYING -> DELIVERED`；超过 `max-attempts` 后为 `DEAD`（死信），
  死信保留 `attempts` 与最后一次失败原因 `lastError`，可通过上述接口查询。

### 退避重试

首次投递立即在线程池执行；失败后按 `backoff-base-ms * 2^(n-1)`（封顶 `backoff-max-ms`）
由调度线程安排重试。每个事件任一时刻只有一个投递任务，不会因重试产生重复入队/并发投递。

### 签名与防重放（接收方视角）

每次投递携带三个头：

| 头 | 含义 |
| --- | --- |
| `X-Event-Id` | 事件唯一标识，接收方据此做幂等去重 |
| `X-Timestamp` | 投递时间 epoch 毫秒，配合允许偏移识别重放 |
| `X-Signature` | HMAC-SHA256 签名（十六进制小写） |

签名内容（`\n` 分隔）：

```
signature = HMAC_SHA256(secret, eventId + "\n" + timestamp + "\n" + rawBody)
```

内置接收方 `POST /internal/receiver` 演示了完整校验：

1. 用相同密钥与规范串重算签名，常量时间比对，不符返回 401 `INVALID_SIGNATURE`；
2. 时间戳偏移超过 `max-timestamp-skew-seconds` 返回 400 `REPLAY_SUSPECTED`；
3. 用 `X-Event-Id` 去重，重复投递在响应中标记 `duplicate=true`。

另有 `POST /internal/receiver/fail` 始终返回 500，用于联调重试与死信。

## 本地验证（测试）

```bash
mvn test                 # 全部 11 个用例
mvn test -Dtest=ConsentConcurrencyTest   # 单类
```

测试日志使用统一 logger `BUSINESS-TESTS`，输出“业务域 / 用例编号 / 断言通过”，可直接从 surefire
控制台看到覆盖了哪些业务与每个用例的结果。

| 测试类 | 覆盖场景 |
| --- | --- |
| `ConsentConcurrencyTest` | 状态机、四类拒绝原因、过期（固定时钟）、**50 并发访问与撤销竞争**、终态约束 |
| `IdempotencyQuotaTest` | **30 线程同键并发只执行一次副作用**且结果一致、同键异内容 409、**20 并发抢额度不超发**、429 携带 remaining/resetAt、回放不扣额度、窗口滚动重置 |
| `EventCallbackTest` | 持续失败按退避重试后**死信并保留原因**、失败后重试成功、重复 eventId 不重复投递、签名确定性与防篡改 |
| `HttpEndToEndTest` | 随机端口全链路 HTTP：授权拒绝码、幂等回放/冲突、配额 429、事件签名投递至内置接收方 |
| `HttpDeadLetterTest` | 真实 HTTP 链路下接收方始终 500，重试耗尽后死信可经 API 查询 |

## 代码结构

```
consent/   授权聚合、状态机、并发存储（每授权锁）
gateway/   幂等记录（每键锁）、固定窗口配额（每客户锁）、组合判定
event/     事件模型、幂等入库、签名工具、投递通道、退避调度、死信
web/       REST 接口与内置回调接收方
common/    拒绝原因、统一异常处理
config/    可配置属性与 Bean 装配
```
