# Facet

Java 25 的授权内核。把"这个人能不能做这件事"从业务代码里拆出来，变成一份可以校验、可以下推到数据库、可以解释的策略。

不是框架，没有运行时依赖。内核模块 `facet-core` 的 `module-info.java` 里一个 `requires` 都没有。

```
io.github.yaotj:facet-core:4.1.0
```

> **稳定性承诺（自 4.0.0 起）**：公开 API 遵循[语义化版本](https://semver.org/lang/zh-CN/)。
> 4.0.0 是 Facet 的第一条稳定线：从它往后的每个 minor（4.1.0、4.2.0…）与 patch（4.0.1…）发布都
> 保持二进制与源码兼容——只新增公开类型或方法，不删除、不重命名、不改签名。破坏性变更只会出现在
> 下一条 major（5.0.0）及以后。3.x 没有兼容性承诺：那是没有外部用户时用来低成本重排 API 的阶段。

## 它解决什么

权限判断散在业务代码里时，三个问题会同时出现：同一条规则在不同接口上实现得不一样；"为什么他能看到"答不出来；"他能看到哪些"只能把全表拉出来逐条判断。

Facet 把策略写成一棵表达式树，然后用**三条路径**求值同一份策略：

| 问题 | 路径 | 实现方式 |
|---|---|---|
| 他能不能看这份文档 | `check` | 解释器，逐层短路 |
| 他能看哪些文档 | `lookupResources` | 编译成**一条** SQL，不回应用层循环 |
| 这份文档谁能看 | `lookupSubjects` | 沿策略正向展开 |

三条路径的一致性有断言守着：200 个随机生成的 schema 上，`lookupResources` 的结果集必须恰好等于逐个 `check` 为 allow 的那些对象；同一份数据在内存适配器与 PostgreSQL 适配器上必须给出逐字符相同的答案。

## 五分钟

用内存适配器，不需要数据库。

```java
import facet.core.eval.Checker;
import facet.core.eval.Ctx;
import facet.core.eval.Explains;
import facet.core.eval.Planner;
import facet.core.ir.Cursor;
import facet.core.ir.ObjectRef;
import facet.core.ir.ObjectType;
import facet.core.ir.Rel;
import facet.core.ir.SubjectRef;
import facet.core.ir.Tuple;
import facet.store.memory.MemoryAttrSource;
import facet.store.memory.MemoryPlanExecutor;
import facet.store.memory.MemoryTupleSource;

import static facet.dsl.rebac.Rebac.anyOf;
import static facet.dsl.rebac.Rebac.direct;
import static facet.dsl.rebac.Rebac.ref;
import static facet.dsl.rebac.Rebac.through;

// 1. 策略。"能看" = 直接是 viewer，或者能看它所在的目录——目录层级有多深就走多深
var inherited = anyOf(direct("viewer"), through("parent", ref("view")));
var schema = facet.dsl.rebac.Rebac.define()
        .type("folder", t -> t
                .tuples("viewer")
                .tuples("parent", "folder")
                .listable("view", inherited))
        .type("doc", t -> t
                .tuples("viewer")
                .tuples("parent", "folder")
                .listable("view", inherited))
        .build();   // build() 里就做校验，构造成功即合法

// 2. 数据。授权关系是元组，不是代码
var DOC = new ObjectType("doc");
var FOLDER = new ObjectType("folder");
var USER = new ObjectType("user");
var VIEWER = new Rel("viewer");
var PARENT = new Rel("parent");
var VIEW = new Rel("view");

var tuples = new MemoryTupleSource().write(
        Tuple.of(new ObjectRef(FOLDER, "eng"), VIEWER, new ObjectRef(USER, "alice")),
        Tuple.of(new ObjectRef(DOC, "readme"), PARENT, new ObjectRef(FOLDER, "eng")));
var attrs = new MemoryAttrSource();

// 3. check
var alice = new SubjectRef.Principal(USER, "alice");
var checker = new Checker(schema, tuples, attrs);
var decision = Ctx.run(Ctx.Request.of(alice),
        () -> checker.check(new ObjectRef(DOC, "readme"), VIEW));

System.out.println(decision.allowed());              // true
System.out.print(Explains.render(decision.explain()));
// AnyOf
//   MISS doc:readme#viewer
//   Through(parent)
//     Ref(view)
//       AnyOf
//         HIT folder:eng#viewer

// 4. 反查：alice 能看哪些 doc
var plan = new Planner(schema, tuples.caps()).plan(DOC, VIEW, Cursor.START, 10);
var executor = new MemoryPlanExecutor(tuples, attrs);
var visible = Ctx.run(Ctx.Request.of(alice), () -> executor.execute(plan).toList());
System.out.println(visible);                         // [doc:readme]
```

`alice` 没有 `doc:readme` 上的任何元组——权限是从目录继承来的，而这条继承在两条路径上都成立。

换成 PostgreSQL 只改装配：`new PgTupleSource(connections, 1024)` / `new PgAttrSource(connections)` / `new PgPlanExecutor(connections)`，策略与业务代码一行不动。反查那一步会变成一条 `WITH RECURSIVE`。

## 为什么要 `Ctx.run`

主体、一致性坐标、请求上下文属性走 `ScopedValue`，不进方法签名。

```java
Ctx.run(Ctx.Request.of(alice).at(revision).withContextAttrs(Map.of("mfa", true)),
        () -> checker.check(doc, VIEW));
```

这不是为了少打字。`check` 是递归的，深度由数据决定；把这些参数放进签名意味着每一层都要转发，而转发时漏掉一个就是一次安全事故。**在 `Ctx.run` 之外调用求值会直接抛异常**，不会退化成某个默认主体——那样越权会看起来像一次正常的 deny。

## 两套 IR

策略编译成 `Perm`（7 个算子），反查编译成 `Plan`（8 个算子）。两者都是 `sealed interface`，所有 `switch` 都不写 `default`——加一个算子，每个没跟上的地方立刻编译失败。

**`Perm`** — check 路径

| 算子 | 含义 |
|---|---|
| `Direct(rel)` | 存储里这条关系上有我 |
| `Ref(rel)` | 同一对象上另一个关系的定义。**递归唯一的入口** |
| `Through(hop, then)` | 顺着 `hop` 跳到别的对象再求 `then` |
| `AnyOf(terms)` / `AllOf(terms)` | 并 / 交 |
| `Minus(base, denied)` | 差。deny 单调，上层不可恢复 |
| `Guarded(base, cond)` | 加条件（ABAC） |

递归在**环境**里而不在树里：`Ref` 去 schema 查定义。这是 Zanzibar 的 `computed_userset`，代价是三条加载期规则（见 `Validator`）。

**`Plan`** — 反查路径

`ScanReverse` → 索引扫描，`Union`/`Intersect`/`Difference` → `UNION ALL`/`INTERSECT`/`EXCEPT`，`ExpandUp` → 一次反向 JOIN，`ExpandUpClosure` → `WITH RECURSIVE`，`Filter` → 属性子查询，`Page` → 键游标 + `LIMIT`。没有一处回到应用层做循环。

## 刻意的限制

这些是设计决定，不是没做完。遇到它们时会明确报错，不会静默给个错答案。

- **反查要求关系声明 `listable`。** 不是所有策略形状都能编成集合代数。不可反查的关系上调 `plan` 会抛 `SchemaException`，而不是回落到"全表扫一遍逐个 check"——那个回落在小数据上看不出来，上量之后是一次全表扫描。
- **顶层必须有 `Plan.Page`。** 结果集大小由数据决定，一个 `doc#view` 可能对应三条也可能三千万条。
- **展开遇到"通配主体 + `Minus`"会拒绝。** `editor@user:*` 减去 `banned@user:alice` 的真实答案是"所有用户除了 alice"，那是个开放集合减一个点，表达它需要在结果里再带一个排除集。check 与反查不受影响——两者都是针对具体主体求值。
- **非分层否定会在加载期被拒。** 递归穿过 `Minus` 的 base 是合法的（有唯一最小模型），穿过 denied 侧不是。
- **多租户用 schema-per-tenant。** 让 `Connections` 交出的连接带各自的 `search_path`。加一列 `tenant` 要改每条查询与每个索引，换来的隔离性还更弱——一次漏加 `WHERE` 就是跨租户泄漏。

## 一次请求的四道上限

授权图的形状由数据决定，所以单次请求的成本必须有兜底。四道上限管的是四件不同的事：

- `maxFanout`（存储声明）—— 一个算子的宽度。
- `maxDepth`（默认 32）—— 一条路径的长度。单位是求值节点数，一层继承约 3–4 个节点。
- `maxNodes`（默认 20000）—— 整棵遍历树的大小。前两个都不限这个：每层四个节点、每个节点指向下一层全部四个节点的图，每个算子都合规，但路径数是指数级的。
- `Deadline`（默认关闭）—— 墙钟。它与 `maxNodes` 互补：预算限工作量、确定可复现，期限限时间、挡的是"存储今天慢十倍"。期限会被传导到语句超时与并发扇出的 scope 超时上，所以它是真的上限。

超限一律抛异常，**不落成 deny**。资源不足的结论是"不知道"，而 deny 会被调用方当成答案。

## PDP（可选）

`facet-pdp-http` 是一个基于 `jdk.httpserver` + 虚拟线程的参考服务端。

| 端点 | 方法 | 能力 |
|---|---|---|
| `/v1/check` `/v1/check-bulk` | POST | READ |
| `/v1/lookup-resources` `/v1/lookup-subjects` | POST | READ |
| `/v1/watch` | POST | READ |
| `/v1/schema` | GET | READ |
| `/v1/schema` | POST | WRITE |
| `/v1/relationships` `/v1/relationships/delete` | POST | WRITE |
| `/v1/relationships/read` | POST | READ |
| `/v1/healthz` | GET | — |

可选能力都在 `PdpServer.Extras` 上，默认全关，用 wither 显式打开：判定缓存、有界陈旧读、远程下发 schema、审计、观测、请求期限、运维读写、变更流。

```java
PdpServer.Extras.NONE
        .withCache(facet.core.spi.DecisionCache.bounded(10_000))
        .withDeadline(Duration.ofMillis(500))
        .withMetrics(myMetrics);
```

**默认关闭是刻意的。** 变更流等价于一份持续的增量导出；运维接口一次调用能清空整个库。这两件事应当是部署方明确决定要开的。

状态码上区分了"能不能重试"：`422` 这个请求本身无法求值，`503` 存储暂时不可用（带 `Retry-After`），`504` 超过请求期限，`409` 变更流起点已被历史回收清掉、必须丢弃本地缓存重来。混成同一个码，网关就只能在"全都重试"和"全都不重试"之间选，两个都错。

## 变更流

`DecisionCache` 只靠 TTL 失效的话，授权变更到生效之间有一个窗口，而那个窗口里被收回的权限仍然放行。变更流让客户端做精确失效。

存储层本来就具备条件：撤销是闭区间不删行，所以"某条授权在哪个坐标被撤销"就记在 `rev_to` 上。

两点必须知道：

- **批次边界永远落在坐标上。** 一次写入是原子的，切成两批会让缓存看到一份改了一半的集合。`limit` 因此是软上限。
- **历史回收会截断变更流。** `compact` 真正删掉已关闭的行，而那些行是撤销记录的唯一载体。从早于回收水位的坐标拉变更，会拿到一份**缺了撤销**的清单。所以那种情况直接报 `409`——收到它的唯一正确处置是丢弃缓存、从当前 HEAD 重建。

## 模块

```
facet-core                内核。零依赖，两套 IR、三条求值路径、六个端口
facet-dsl-rebac           关系式策略的 Java builder
facet-dsl-rbac            角色式策略
facet-dsl-abac            属性条件
facet-ir-json             schema 的 JSON 编解码，支持进程外加载与热更新
facet-store-memory        内存适配器。参考实现，也是测试基线
facet-store-pg            PostgreSQL 适配器。时效区间 + 递归 CTE
facet-fanout-structured   并行扇出（StructuredTaskScope，预览特性）
facet-pdp-http            HTTP PDP 参考实现
facet-testkit             随机场景生成、判定矩阵、golden 快照、策略变更影响分析
```

适配器通过端口接入，不是继承：`TupleSource`、`AttrSource`、`Fanout`、`PlanExecutor`、`Metrics`。内核不提供 `PlanExecutor` 的通用实现——通用实现只能是"全部拉到内存再算集合"，那会让每个适配器都失去下推的机会。

## 构建

需要 **JDK 25** 与 **Maven 4**（根 pom 用的是 4.1.0 模型，`<subprojects>` 与坐标推断在 Maven 3.9 上不工作）。

```bash
mvn test        # 224+ 个用例
```

跑 PostgreSQL 相关用例需要 Docker（testcontainers）。几个大规模用例默认跳过：

```bash
mvn test -Dfacet.scale=true          # 60 层深链
mvn test -Dfacet.scale.large=true    # 千万行量级的反查计划测量
mvn test -Dfacet.golden.update=true  # 重新生成 SQL / explain 的 golden 基线
```

## 状态

`3.0.0`，已发布到 Maven Central（首个发布版为 1.0.0）。

发布配置已就绪（`mvn -Pcentral deploy`），剩下的是 Central 账号与 GPG 密钥这类只能由发布者本人完成的步骤。

诚实地说：测试断言的是语义正确性与跨实现一致性，但"手写 schema 啰嗦不啰嗦"、"`Ctx.run` 包在业务代码里别不别扭"这类问题只有真实接入才能暴露。

3.0.0 是一次破坏性重构：用设计模式把 HTTP 层的职责进一步显式化——`PdpServer` 的九条端点注册收进一张声明式路由表（`Route` 注册表，统一由 `dispatch` 派发），异常到状态码的映射从 `instanceof` 链改为责任链（`HttpErrorMapper` 上每个异常类型一个可独立测试的处理器）。之所以在 2.0.0 之后紧接着做，是因为 README 早已承认尚无外部真实使用者——这正是做破坏性重构成本最低的时机；之后才会按语义化版本承诺跨 minor 兼容。内核的 `Perm` 仍是 `sealed interface` + 穷尽 `switch` 的解释器写法（新增算子让编译器把全部求值器一次列错），未改动。
