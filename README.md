# MOJ Online Judge — Backend

MOJ（Moj Online Judge）在线判题系统后端，提供用户认证、题库管理、代码提交、异步判题与判题结果展示等核心能力。

> 🧩 **配套仓库**：判题执行由独立的代码沙箱服务完成 → **[moj-code-sandbox](https://github.com/mothieras/moj-code-sandbox)**（Docker 容器池隔离运行用户代码）。

## 技术栈

- **Java 21** + **Spring Boot 3.4**
- **MyBatis Plus** + **MySQL** — 数据持久化
- **Redis** + **Spring Session** — 分布式登录态 + Cache-Aside 缓存（防穿透/雪崩）
- **RabbitMQ** — 判题流程异步解耦（手动 ACK / 发布确认 / 死信队列兜底）
- **Knife4j** — API 文档与在线调试
- **Hutool** / **Commons Lang3** — 通用工具

## 快速启动

```bash
# 1. 一键拉起依赖中间件（MySQL / Redis / RabbitMQ），首次启动自动建库建表
docker-compose -f docker-compose.dev.yml up -d

# 2. 启动后端（默认 dev 环境）
./mvnw spring-boot:run

# 3. 访问 API 文档（Knife4j）
open http://localhost:8121/api/doc.html
```

> 完整判题需配合代码沙箱：将 `codesandbox.type` 设为 `remote` 并启动 [moj-code-sandbox](https://github.com/mothieras/moj-code-sandbox)；仅本地测试主流程可设为 `example`。

## API 概览

| 模块 | 端点 | 说明 |
|------|------|------|
| 用户 | `/api/user/register` | 注册 |
| 用户 | `/api/user/login` | 登录 |
| 用户 | `/api/user/logout` | 注销 |
| 用户 | `/api/user/get/login` | 获取当前用户 |
| 题目 | `/api/question/add` | 添加题目（管理员） |
| 题目 | `/api/question/list/page/vo` | 分页查询题目 |
| 判题 | `/api/question_submit/do` | 提交代码进行判题 |
| 判题 | `/api/question_submit/list/page` | 查询提交记录 |

完整接口文档见 Knife4j 页面。

## 项目结构

```
com.moj
├── controller/          # REST 控制器
├── service/
│   └── impl/            # 业务逻辑实现
├── mapper/              # MyBatis Mapper
├── model/
│   ├── entity/          # 数据库实体
│   ├── dto/             # 请求/响应 DTO
│   ├── vo/              # 视图对象
│   └── enums/           # 枚举
├── judge/               # 判题核心
│   ├── codesandbox/     #   沙箱（工厂+代理模式）
│   └── strategy/        #   判题策略（策略模式）
├── config/              # Spring 配置
├── aop/                 # AOP（权限校验、日志）
├── common/              # 通用类（BaseResponse、ErrorCode）
├── constant/            # 常量
├── exception/           # 全局异常处理
└── utils/               # 工具类
```

## 判题流程

```
QuestionSubmitController        ← 用户提交代码
        ↓
JudgeServiceImpl                ← 编排：获取题目 & 提交信息
        ↓
CodeSandboxRouter.select()     ← Spring Bean 根据配置选择沙箱
        ↓
CodeSandboxProxy                ← 代理增强（日志记录）
        ↓
Sandbox.executeCode()           ← 在隔离环境执行代码
        ↓
classifySandboxResult()         ← 短路检查：编译错误/TLE/RE/系统错误
        ↓
JudgeManager.doJudge()          ← 按语言选择判题策略
        ↓
JudgeStrategy.doJudge()         ← 对比输出 / 检查时间内存限制
        ↓
更新 QuestionSubmit 状态         ← 结果持久化
```

## 判题状态机

```
         提交代码
            ↓
       WAITING (0) ─────────────── RabbitMQ 消费
            ↓
       RUNNING (1) ─────┬──── 沙箱执行成功 ────→ SUCCEED (2)  [Accepted]
            │            ├──── 编译错误/TLE/RE/WA ──→ FAILED (3)  [具体 JudgeInfo]
            │            ├──── 沙箱基础设施异常 ──→ 重置 WAITING，重试 (最多3次)
            │            └──── 内部异常/Bug ──→ FAILED (3)  [System Error]
            │
       FAILED (3) ←────── 重试耗尽 / 不可重试错误
```

## 错误处理策略

| 异常类型 | 示例 | 处理方式 |
|---------|------|---------|
| **业务失败** | 编译错误、WA、TLE、RE、MLE | 直接写 FAILED + 具体 JudgeInfo 消息，不重试 |
| **基础设施异常** (`SandboxException`) | 沙箱连接超时、HTTP 错误、沙箱不可用 | 重置状态为 WAITING，抛给 JudgeConsumer 重试（最多 3 次） |
| **内部异常** (RuntimeException 等) | 意外 NPE、DB 错误 | 安全网：强制置 FAILED + System Error，消息进死信队列 |

## 测试

```bash
# 单元测试（无需启动沙箱，无需 Docker）
./mvnw clean test

# 集成测试（需先启动 moj-code-sandbox + Docker）
./mvnw verify -DskipITs=false
```

## 设计模式

| 模式 | 应用位置 | 说明 |
|------|----------|------|
| 策略模式 | `judge/strategy/` | `JudgeStrategy` 接口 + `DefaultJudgeStrategy` / `JavaLanguageJudgeStrategy`，按语言分发判题逻辑 |
| 工厂模式 | `judge/codesandbox/CodeSandboxRouter` | 根据 `codesandbox.type` 配置选择沙箱实现（`@Deprecated`: `CodeSandboxFactory` 静态工厂版本） |
| 代理模式 | `judge/codesandbox/CodeSandboxProxy` | 在沙箱调用前后增加日志，不侵入沙箱实现 |

## 配置

```yaml
# 代码沙箱类型：example（本地示例）/ remote（远程）/ thirdParty（第三方）
codesandbox:
  type: remote
```

多环境配置：`application.yml`（dev 默认）、`application-test.yml`、`application-prod.yml`。

> 生产/测试环境的敏感配置通过环境变量注入、不入库：`MYSQL_PASSWORD`、`KNIFE4J_PASSWORD`。
