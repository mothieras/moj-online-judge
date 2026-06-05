# MOJ 判题后端与代码沙箱优化设计文档

**日期**：2026-06-05  
**范围**：`moj-backend` 与 `moj-code-sandbox` 的判题链路、沙箱调用、容器隔离、测试边界和求职展示优化。  
**定位**：求职项目优先，目标是让项目经得起代码审阅和面试深挖，而不是扩展成生产级 Online Judge 平台。

## 背景

MOJ 当前已经具备完整主链路：

```
用户提交代码
  -> moj-backend 写入 QuestionSubmit(WAITING)
  -> RabbitMQ 异步投递提交 id
  -> JudgeConsumer 消费消息
  -> JudgeServiceImpl 调用远程代码沙箱
  -> moj-code-sandbox 在 Docker 容器中编译 / 运行用户代码
  -> backend 按输出、时间、内存生成 JudgeInfo
  -> 更新 QuestionSubmit 为 SUCCEED 或 FAILED
```

这条链路已经能体现异步判题、Docker 隔离、容器池复用、Redis 缓存、RabbitMQ 消息解耦等亮点。后续优化应围绕三个问题展开：

1. **可验证**：本地和 CI 能稳定跑测试，不被外部服务偶然状态影响。
2. **可靠性**：提交不能卡在中间态，失败原因要可解释。
3. **安全边界**：沙箱对不可信代码的限制要明确、可测试、可面试讲清楚。

## 目标 / 非目标

**目标**

- 后端默认单元测试不依赖远程沙箱；远程沙箱链路作为集成测试单独验证。
- 判题状态机清晰，失败提交不会长期停留在 `RUNNING`。
- 后端与沙箱的接口契约明确：当前只支持 Java，语言、代码、输入边界都有校验。
- 沙箱容器复用具备更稳健的清理和坏容器替换策略。
- README 和设计文档能说明项目亮点、取舍和后续扩展路线。

**非目标**

- 不做完整比赛系统、题解系统、排名系统、权限后台。
- 不优先扩展多语言；多语言只作为后续可扩展点。
- 不引入复杂调度系统、Kubernetes、分布式沙箱集群。
- 不追求生产级安全沙箱；重点是当前 Docker 隔离方案的边界和验证。

## 当前基线

### 后端

- 使用 Spring Boot + MyBatis Plus + MySQL 管理用户、题目、提交记录。
- 使用 RabbitMQ 异步解耦提交和判题。
- 使用 Redis + Spring Session 支撑登录态和题目缓存。
- 判题入口集中在 `JudgeServiceImpl`，按沙箱返回结果生成 `JudgeInfo`。
- 当前语言契约收敛为 Java，避免后端允许 C++/Go 但沙箱只支持 Java 的错配。
- 远程沙箱 URL 和 auth secret 已配置化。
- 密码从固定盐 MD5 迁移到 BCrypt，并兼容旧密码升级。

### 沙箱

- 使用 Docker 常驻容器池，避免每次提交创建 / 销毁容器。
- 容器禁网、只读根文件系统、非 root 用户、内存限制、禁 swap、`pids-limit`。
- 编译和运行都在容器内完成，避免宿主 JDK 与容器 JDK 不一致。
- stdout / stderr 分流，能区分正常输出和运行时错误。
- 超时后尝试 kill 失控进程，并在归还容器前清理 `/box`。

## 优化方案概览

建议分三阶段推进。

### Phase 1：测试边界和项目可运行性

这是最优先的优化，因为它直接影响审阅可信度。

**1. 后端测试分层**

当前后端默认配置是 `codesandbox.type=remote`。默认单元测试如果直接调用 `CodeSandboxFactory.newInstance(type)`，会依赖 `localhost:8090` 是否有沙箱服务，导致 `mvn clean test` 不稳定。

建议：

- 单元测试固定使用 `example` 沙箱，验证后端业务逻辑。
- 远程沙箱链路改成 `*IT` 集成测试，放到 `mvn verify` 或单独 profile。
- README 明确：
  - `./mvnw clean test`：不需要启动沙箱。
  - `./mvnw verify`：需要先启动 `moj-code-sandbox` 和 Docker。

**2. 沙箱 Java 版本口径统一**

如果 `moj-code-sandbox` 继续声明 Java 8，则源码不要使用 Java 11+ API，例如 `String.isBlank()`。  
如果希望用当前 SDKMAN JDK 21 作为开发基线，则 README、POM、运行说明应统一升级，避免“项目写 Java 8、实际用 Java 21 验证”的口径不一致。

推荐：求职项目统一到 Java 21 或 Java 17。两个项目技术栈一致，减少解释成本。

**3. 一键 smoke**

增加一个最小 smoke 流程，证明两项目能联动：

```
启动依赖中间件
启动 moj-code-sandbox
启动 moj-backend
提交一段 Java 代码
轮询提交结果
断言最终状态是 SUCCEED 或 FAILED，且不为 RUNNING
```

这条 smoke 不需要覆盖所有业务，只证明主链路真实可用。

## Phase 2：判题状态机与错误分类

当前首要目标是“不卡死”，即异常不能让提交长期停在 `RUNNING`。这已经比单纯抛异常更可靠。

建议进一步把判题失败分为两类：

| 类型 | 示例 | 处理 |
|------|------|------|
| 业务 / 判题结果失败 | 编译错误、运行错误、超时、答案错误 | 直接写 `FAILED`，记录对应 `JudgeInfo` |
| 系统 / 基础设施异常 | 沙箱连接失败、HTTP 超时、RabbitMQ 消费异常 | 可选择重试，最终失败写 `System Error` |

求职项目可以保留当前策略：沙箱调用异常直接 `FAILED`，优先保证提交不挂死。  
如果继续增强，则只对网络异常保留 RabbitMQ 重试：

```
WAITING -> RUNNING
  -> 编译错误 / WA / TLE / RE      -> FAILED
  -> 沙箱网络异常且 retry < max    -> 恢复 WAITING 或抛给 consumer 重试
  -> 沙箱网络异常且 retry exhausted -> FAILED(System Error)
  -> AC                            -> SUCCEED
```

这里要避免一个反模式：`JudgeServiceImpl` 把所有异常吞掉后返回，`JudgeConsumer` 就会正常 ack，导致 RabbitMQ 重试逻辑形同虚设。是否保留重试，必须由状态机显式决定。

## Phase 3：沙箱边界和容器池健壮性

### 请求边界

沙箱接口需要在入口层限制不可信输入：

- `code` 不能为空，且限制最大长度。
- `inputList` 最大数量限制。
- 单个 input 最大长度限制。
- 输出最大长度限制，防止用户程序刷爆 stdout/stderr。
- `language` 必须是明确支持的值，当前只允许 `java`。

错误响应不要返回 `null` body。即使鉴权失败，也应返回结构化错误，方便后端记录和排查。

### 容器复用边界

容器池的关键风险不是“能不能复用”，而是“脏容器会不会被复用”。

建议规则：

- 每次执行前确认容器健康。
- 执行超时后 kill 用户进程。
- 清理 `/box` 失败时，不归还容器，直接销毁并补新容器。
- kill 失败或健康检查失败时，不归还容器。
- 记录容器替换次数，便于排查恶意代码或资源泄露。

### 安全测试

保留并补强这些测试用例：

- 读宿主文件失败。
- 写非 `/box` 目录失败。
- 网络访问失败。
- fork / 多进程被 `pids-limit` 限制。
- sleep 超时后返回 TLE。
- 大内存分配触发内存限制。
- 大输出被截断或判为输出超限。

这些测试比继续增加业务功能更有展示价值。

## 架构边界建议

### 后端沙箱选择

当前 `CodeSandboxFactory` 如果通过 static 方法持有 Spring `ApplicationContext`，虽然能跑，但边界不够干净。

建议后续改为：

```
JudgeServiceImpl
  -> CodeSandboxRouter
      -> ExampleCodeSandbox
      -> RemoteCodeSandbox
      -> ThirdPartyCodeSandbox
```

`CodeSandboxRouter` 是普通 Spring Bean，读取 `codesandbox.type`，返回对应实现。这样比静态工厂更符合 Spring DI，也更容易测试。

### 判题编排拆分

`JudgeServiceImpl.doJudge` 可以按职责拆出私有方法或小组件：

- `loadSubmitAndQuestion`
- `markRunning`
- `callSandbox`
- `classifySandboxResult`
- `applyJudgeStrategy`
- `finishSubmit`
- `failSubmit`

目标不是为了抽象而抽象，而是让面试时能清楚讲出状态机和错误处理。

## 测试策略

### 后端单元测试

| 模块 | 测试点 |
|------|--------|
| `QuestionSubmitServiceImpl` | 不支持语言、空代码、超长代码、提交后发送消息 |
| `JudgeServiceImpl` | AC、WA、编译错误、运行错误、超时、沙箱异常 |
| `JudgeConsumer` | 重复消息、非 WAITING 消息、异常重试、最终死信 |
| `RemoteCodeSandbox` | URL 配置、auth header、空响应、连接失败 |
| `QuestionServiceImpl` | Redis 命中、未命中、空值哨兵、Redis 降级 |

### 沙箱测试

| 模块 | 测试点 |
|------|--------|
| `MainController` | 鉴权失败、空请求、空代码、输入用例过多 |
| `JavaDockerCodeSandbox` | 编译失败、正常输出、运行错误、超时 |
| `ContainerPool` | 借还复用、坏容器替换、池耗尽超时 |
| `DockerContainerProvider` | 容器参数：禁网、只读 rootfs、非 root、内存和 pids 限制 |
| `ContainerExecutor` | stdout/stderr 分流、退出码、超时 kill |

### 集成测试

集成测试只验证跨服务主链路：

```
backend -> RemoteCodeSandbox -> sandbox -> Docker container -> backend judge result
```

它不应混在默认单元测试里。建议使用 `*IT` 命名，并在 README 说明前置条件。

## README 展示建议

后端 README 建议新增：

- 一张判题流程图。
- “本地单测”和“端到端联调”分开的命令。
- 判题状态机表。
- 异常处理策略表。

沙箱 README 建议新增：

- 容器隔离参数表。
- 容器池复用前后的性能对比说明。
- 安全用例说明。
- 当前只支持 Java 的明确声明。

## 面试讲点

| 方向 | 可以怎么讲 |
|------|------------|
| 异步判题 | 用户提交后快速返回提交 id，后台通过 RabbitMQ 消费判题，避免接口阻塞 |
| 状态机 | 用 `WAITING/RUNNING/SUCCEED/FAILED` 保证提交状态可追踪，异常时最终落库，避免卡死 |
| 沙箱隔离 | Docker 禁网、非 root、只读 rootfs、内存和进程数限制，降低不可信代码风险 |
| 容器池 | 预热容器并复用，减少创建 / 销毁容器开销；归还前清理工作目录 |
| 缓存 | 题目详情使用 Cache-Aside，空值哨兵防穿透，TTL 抖动防雪崩 |
| 取舍 | 当前只支持 Java，先保证主链路可靠；多语言和沙箱集群作为后续扩展 |

## 推荐落地顺序

1. **测试分层**：默认 `mvn clean test` 不依赖远程沙箱；远程链路进入集成测试。
2. **Java 版本统一**：后端与沙箱统一 Java 版本口径。
3. **状态机测试**：补 `JudgeServiceImpl` 和 `JudgeConsumer` 的关键单测。
4. **沙箱边界**：补 code/input/output 限制，清理失败时替换容器。
5. **文档补强**：README 增加运行命令、架构图、测试策略和面试讲点。

## 成功标准

- `moj-backend` 默认单元测试可在不启动沙箱的情况下通过。
- `moj-code-sandbox` 默认测试可稳定通过。
- 端到端 smoke 能证明提交不会停留在 `RUNNING`。
- README 能清楚解释“如何运行、如何验证、如何排查、边界在哪里”。
- 面试时可以围绕异步判题、容器池、安全隔离、缓存、状态机讲出具体实现和取舍。
