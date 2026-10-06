# Facet 发布到 Maven Central 可行性评估

> 评估日期：2026-10-06 · 代码基线：`1.0.0` · 评估方式：实机探针 + Sonatype 官方文档核实
> **状态：构建配置已补齐并实测通过（`mvn clean install` 零失败，10 个模块产物三件套齐全）。
> 源码零改动。仅剩需你本人完成的账号/密钥步骤。**

---

## 结论速览

| 问题 | 结论 |
|---|---|
| GitHub 仓库是否需要"上传到公共仓库"？ | **不需要**。`yaotj/facet` 已是 `PUBLIC`，代码已推送 |
| 能否发布到 Maven Central？ | **能**，但有前置工作 |
| 最大的技术障碍 | **不是**我最初以为的 Java 版本，而是 pom 元数据与发布插件缺失 |
| 需要改代码吗？ | **不需要**。核心代码零改动即可发布 |

---

## 一、先纠正一个前提

`yaotj/facet` 当前状态：

```
visibility: PUBLIC
defaultBranchRef: main
```

代码已通过 SSH 推送。因此若"公共仓库"指 GitHub，**该步骤早已完成**。

本文档评估的是另一个含义：**发布到公共制品仓库 Maven Central**（`repo1.maven.org`），让下游能写
`io.github.yaotj:facet-core:1.0.0` 直接依赖。

---

## 二、实测：编译目标版本的下限（附纠正）

### 实机探针结果

用 `-Dmaven.compiler.release=N` 逐档实测全反应器编译（仅命令行覆盖，未改文件）：

| release | 结果 | 阻塞原因 |
|---|---|---|
| 17 | ❌ 失败 | 错误集中在 `facet-core` 11 个文件：switch 模式匹配 + 解构 + 未命名变量（均为 Java 21 特性） |
| 21 | ❌ 失败 | 仅剩 **8 条**，全部是「未命名变量 `_`」（Java 22 特性） |
| 22 | ❌ 失败 | `ScopedValue` 是**预览 API**，默认禁用（3 条错误） |
| 23 | ❌ 失败 | 同上，8 条（`ScopedValue` 预览） |
| 24 | ❌ 失败 | 同上，8 条（`ScopedValue` 预览） |
| **25** | ✅ **成功** | — |

### 需要纠正的说法

我最初口头断言"Central 要求 release ≤ 17，因此 `release 25` 会被拒收"。
**核实官方文档后，这个说法不成立。** Sonatype 官方
[Requirements 文档](https://central.sonatype.org/publish/requirements/) 列出的硬性要求是：

- sources jar + javadoc jar（每个 artifact 都要）
- `.md5` / `.sha1` 校验和
- GPG/PGP 签名（每个文件配 `.asc`）
- POM 必须含：`name`、`description`、`url`、`licenses`、`developers`、`scm`
- 版本号不得以 `-SNAPSHOT` 结尾

**该清单中没有 Java 版本上限这一条。** 官方 FAQ 亦未将 class file 版本列为拒绝条件。

因此：**`release 25` 不构成发布阻塞。** 项目保持现状即可。

### 技术依赖确认

代码确实依赖 JDK 21+ 的现代 API（这解释了为何降级困难，但**不阻碍发布**）：

| API | 位置 | 引入版本 |
|---|---|---|
| `ScopedValue` | `facet-core/.../eval/Ctx.java:23` | 21（预览）→ **25 转正** |
| `StructuredTaskScope` | `facet-fanout-structured/.../StructuredFanout.java:11` | 21（预览）→ **25 转正** |

已在 JDK 25 上实测确认两者均为正式 API（非预览），`javap` 验证 `java.lang.ScopedValue` 为 `public final class`。

**含义**：Facet 内核绑定 JDK 25，这是明确的取舍而非疏漏。若未来想支持更低运行时，
需要重写 `Ctx` 的上下文传播（去掉 `ScopedValue`）与整个 `facet-fanout-structured` 模块——那是独立的架构工作，与发布无关。

---

## 三、Central 硬性要求的差距清单

### 3.1 POM 元数据（父 pom `pom.xml`）—— ✅ 已补齐

| 要求项 | 状态 | 内容 |
|---|---|---|
| `name` | ✅ 已有 | — |
| `description` | ✅ 已有 | — |
| `url` | ✅ **已补** | `https://github.com/yaotj/facet` |
| `licenses` | ✅ 已有 | Apache-2.0 |
| `developers` | ✅ **已补** | id/name/url |
| `scm` | ✅ **已补** | connection / developerConnection / url |

### 3.2 发布插件 —— ✅ 已配置

| 插件 | 位置 | 说明 |
|---|---|---|
| `maven-source-plugin` | 默认构建 | 每个 jar 生成 `-sources.jar` |
| `maven-javadoc-plugin` | 默认构建 | 每个 jar 生成 `-javadoc.jar` |
| `maven-gpg-plugin` | **`central` profile** | 签名只在发布时启用 |
| `central-publishing-maven-plugin` | **`central` profile** | 上传 Central，`autoPublish=false` |

**实测结果**：`mvn clean install` 零失败，10 个发布模块的 `jar` + `sources.jar` + `javadoc.jar` 三件套齐全。

### 3.3 实施过程中解决的三个真实问题

**① javadoc 与编译器对同一 API 的判断不一致**

`facet-fanout-structured` 编译通过，但 javadoc 报 `StructuredTaskScope is a preview API`。
原因：javadoc **不继承** `maven.compiler.release` 与 compilerArgs，按"当前 JDK + 默认 preview 开关"解析源码。
解决：显式配置 `<release>` + `additionalOptions` 里的 `--enable-preview`。

> 踩坑记录：maven-javadoc-plugin 3.11.2 **没有** `enablePreview` 参数（用 `-X` 看到的同名配置项属于 compiler 插件的 Mojo，写在 javadoc 配置里不生效），必须走 `additionalOptions`。

**② 签名不能留在默认构建里**

gpg-plugin 绑在 `verify` 阶段，留在默认构建会让每次本地 `mvn install` 都要求 GPG 私钥——
没有密钥的开发者和 CI 普通构建直接失败。已移入 `central` profile。

**③ `facet-benchmark` 不该发布**

JMH 基准不是给下游用的库。已设 `maven.deploy.skip` / `maven.source.skip` / `maven.javadoc.skip` / `gpg.skip`。
> 注：主 jar 仍会生成，因为 maven-jar-plugin 的 jar goal 没有 skip 参数；但 `deploy` 已跳过，不会上传 Central。

### 3.4 仍需你本人完成（我无法代办）

1. 注册 [Sonatype Central Portal](https://central.sonatype.com/) 账号
2. 验证 namespace `io.github.yaotj`
3. 生成 GPG 密钥对，将公钥上传到 Central
4. 生成 User Token（用户名 + 密码，存 `~/.m2/settings.xml`）

---

## 四、多模块聚合发布的额外考量

本项目是 12 模块聚合，父 pom `packaging` 为 `pom`。要点：

- **只在父 pom 上配置发布插件**，子模块自动继承（与 `<subprojects>` 配合）
- `packaging=pom` 的父模块本身不产 jar，**无需** sources/javadoc（Central 对 `pom` 打包豁免）
- 11 个 jar 模块都需要 sources + javadoc + 签名
- 建议配 `<autoPublish>`，验证通过后自动同步（否则需手动点 Publish）

### 一个需要预先决策的点

Central 政策明确：**一旦发布不可撤回、不可修改**（[FAQ](https://central.sonatype.org/faq/can-i-change-a-component/)）。

而 README 现状写明"还没有被任何外部使用者验证过"。若现在发布 1.0.0，
等于把未经真实接入验证的 API 固化为不可变更的公开契约。

**建议**：要么先按 `0.1.0` 发一个预览版（验证后再升 1.0.0），
要么确认 API 已足够稳定、直接发 1.0.0。

---

## 五、改动量评估

| 类别 | 文件 | 状态 |
|---|---|---|
| 父 pom 版本号 | `pom.xml` | ✅ 已改（1.0.0） |
| 父 pom 元数据 | `pom.xml` | ✅ 已补 url / developers / scm |
| 父 pom 发布插件 | `pom.xml` | ✅ 已配 source / javadoc（默认）+ gpg / central-publishing（profile） |
| benchmark 排除发布 | `facet-benchmark/pom.xml` | ✅ 已配 skip 属性 |
| README 状态段 | `README.md` | ✅ 已改 |
| **源码** | — | **零改动**（已实测确认） |

全部改动集中在 2 个 pom 的构建配置，不触及任何 Java 代码。

---

## 六、发布流程（配置已就绪）

```bash
# 1) 确认产物齐全（不需要私钥）
mvn clean install

# 2) 正式发布（需要 GPG 私钥 + ~/.m2/settings.xml 里的 central 凭据）
mvn -Pcentral deploy
```

`settings.xml` 需要：
```xml
<server>
  <id>central</id>
  <username>你的 Central Token 用户名</username>
  <password>你的 Central Token 密码</password>
</server>
```

首次发布建议保持 `autoPublish=false`，到 Portal 页面确认 validation 结果无误后再手动点 Publish。

---

## 七、行动清单

### ✅ 我已完成
- [x] 父 pom 补齐 `<url>`、`<developers>`、`<scm>`
- [x] 配置 source / javadoc / gpg / central-publishing 插件
- [x] `facet-benchmark` 排除出发布链路
- [x] 实测验证：`mvn clean install` 零失败，10 个模块三件套齐全
- [x] `-Pcentral` profile 可正确解析

### ⬜ 需要你做
- [ ] 注册 [Central Portal](https://central.sonatype.com/) 账号
- [ ] 验证 namespace `io.github.yaotj`
- [ ] 生成 GPG 密钥对，公钥上传到 Central
- [ ] 配 `~/.m2/settings.xml` 的 `central` 凭据
- [ ] **决策**：直接发 1.0.0，还是先发 0.1.0 预览版
- [ ] （可选）在 CI 加一个带密钥的 release workflow

---

## 参考

- [Sonatype Requirements](https://central.sonatype.org/publish/requirements/) — 硬性要求清单
- [Central Publisher Portal Guide](https://central.sonatype.org/publish/publish-portal-guide/) — 发布流程
- [Can I change a component on Central?](https://central.sonatype.org/faq/can-i-change-a-component/) — 不可变更政策
