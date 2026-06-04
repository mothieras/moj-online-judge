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
CodeSandboxFactory.newInstance  ← 根据配置创建沙箱（example/remote/thirdParty）
        ↓
CodeSandboxProxy                ← 代理增强（日志记录）
        ↓
Sandbox.executeCode()           ← 在隔离环境执行代码
        ↓
JudgeManager.doJudge()          ← 按语言选择判题策略
        ↓
JudgeStrategy.doJudge()         ← 对比输出 / 检查时间内存限制
        ↓
更新 QuestionSubmit 状态         ← 结果持久化
```

## 设计模式

| 模式 | 应用位置 | 说明 |
|------|----------|------|
| 策略模式 | `judge/strategy/` | `JudgeStrategy` 接口 + `DefaultJudgeStrategy` / `JavaLanguageJudgeStrategy`，按语言分发判题逻辑 |
| 工厂模式 | `judge/codesandbox/CodeSandboxFactory` | 根据 `codesandbox.type` 配置动态创建沙箱实例 |
| 代理模式 | `judge/codesandbox/CodeSandboxProxy` | 在沙箱调用前后增加日志，不侵入沙箱实现 |

## 配置

```yaml
# 代码沙箱类型：example（本地示例）/ remote（远程）/ thirdParty（第三方）
codesandbox:
  type: remote
```

多环境配置：`application.yml`（dev 默认）、`application-test.yml`、`application-prod.yml`。

> 生产/测试环境的敏感配置通过环境变量注入、不入库：`MYSQL_PASSWORD`、`KNIFE4J_PASSWORD`。
