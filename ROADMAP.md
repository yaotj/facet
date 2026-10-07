# Facet 演进路线

本文件是 Facet 的演进规划，对应「内核已成型、但还没变成能用的产品」这个拐点。它与
`README.md` 的自陈一致：测试断言的是语义正确性与跨实现一致性，但「手写 schema 啰嗦不啰嗦」、
「`Ctx.run` 包在业务代码里别不别扭」这类问题只有真实接入才暴露。

当前家底（2026-10-07，3.0.0 已发布到 Maven Central）：

- 内核 `facet-core`：ACL/RBAC/ABAC/ReBAC 编译到同一份 `Perm` IR；`module-info` 零三方依赖。
- 驱动侧：`facet-pdp-http`（HTTP 服务）+ 新增的 `facet-sdk`（进程内门面）。
- 编译前端：`facet-dsl-rebac` / `facet-dsl-rbac` / `facet-dsl-abac`（Builder 形态）。
- 适配器：`facet-store-memory`（参考实现）、`facet-store-pg`、`facet-fanout-structured`。
- 基建：`facet-ir-json`、`facet-testkit`、`facet-benchmark`（JMH）。

**还没有**：框架集成层（Spring Boot / Quarkus）、更多存储适配（MySQL / DynamoDB / Redis）、
conformance 测试套件、runnable 示例、多租户 namespace 示例。

---

## 阶段 0 — 冻结 API 的裁决（最紧迫）

3.0.0 已经做了一次破坏性重排，理由是「尚无外部用户、成本最低」。这个窗口每开一天都在收窄。

**待用户拍板**：还有没有「必须破」的设计？

- 有 → 趁现在一次破完，发 **4.0.0 作为稳定线**（stable line），之后承诺跨 minor 兼容。
- 没有 → 明确 3.x 起承诺 SemVer 跨 minor 兼容，并把这条写进 `README.md`。

建议：先把「必须破」的收尾（见阶段 1 的缓存类型搬迁），再在 4.0.0 上冻结公开 API。

## 阶段 1 — 可集成层（最高杠杆）

直接回答 README 那两个「只有真实接入才暴露」的问题。

- **框架无关 SDK（已落地 `facet-sdk`）**：封装 `Ctx.run`、装求值器、给 `check` / `checkAll` /
  `lookup` / `whoCan` 四个贴合场景的方法。下一步要让它也能缓存：
  - **把 `DecisionCache` / `RevisionSource` 从 `facet-pdp-http` 搬进 `facet.core.spi`**。
    现在缓存与坐标陈旧策略被绑在 HTTP 模块上，进程内用法复用不了。搬完之后 HTTP 服务与 SDK
    共享同一套缓存语义，也顺手消掉 `facet-sdk` 与 `facet-pdp-http` 里重复的键计算逻辑。
- **Spring Boot Starter**：自动装 `Facet` Bean，把 `TupleSource` / `AttrSource` / `PlanExecutor`
  接成配置属性，提供类型安全的判定客户端与 `@PreAuthorize` 风格的注解。
- **Quarkus 扩展**：同上，走 Quarkus 的 bean 发现与配置体系。

## 阶段 2 — 存储广度

- MySQL 适配器（PG 的 `Plan` 编译策略可复用，主要是方言）。
- DynamoDB / 通用 KV 适配器：**反向索引是真正难点**——KV 形态下没有 SQL 的反向扫描，
  需要新的 `rewrite → 查询` 编译策略，复用 `Plan` 这套集合代数。
- Redis 缓存后端（配合阶段 1 搬迁后的 `DecisionCache`）。

## 阶段 3 — 信任基建

- **Zanzibar / Zookies 式 conformance 测试套件**：证明语义正确，而不只是「现有测试不红」。
  这是争取外部信任的关键材料。
- 用 `facet-benchmark`（JMH）把工作预算 / 期限 / 扇出的性能承诺锁死，纳入 CI 门禁。
- OpenTelemetry + 结构化审计（复用已有的 `Metrics` / `AuditSink` 端口）。

## 阶段 4 — 文档与示例

- runnable quickstart、一个「部署 PDP」的故事、多租户 namespace 示例。

---

## 明确不做

- **不在没真实用户前堆功能**：先有阶段 1 带来第一个真实接入者，再按它的痛点排优先级。
- **不给 `facet-core` 加任何依赖**：`module-info` 零 `requires` 是护城河。
- **不把 `Validator` / `Perm` 改成「更花哨」的模式**：`Perm` 的 `sealed interface` + 穷尽
  `switch` 让新增算子时编译器把全部求值器一次列错；`Validator` 的四步校验刻意交织在同一趟
  带位置信息的遍历里，拆成独立链会丢掉「报错指向具体规则」的位置信息——两者都是退化。
