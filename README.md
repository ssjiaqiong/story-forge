# StoryForge

AI 长篇小说创作平台。**单用户空间**（用户即数据隔离单元，无租户概念）+ **超级管理员平台功能**（用户管理、小说管理）。

**文档**（随代码版本管理，只描述已实现功能）：

- [需求规格说明书](./docs/01-需求规格说明书.md)
- [详细设计说明书](./docs/02-详细设计说明书.md)
- [数据库兼容与部署运维](./docs/03-数据库兼容与部署运维.md)
- [文档索引](./docs/README.md)

**许可**：[AGPL-3.0](./LICENSE) · Copyright (C) 2026 Jiaqiong · 源码 <https://gitee.com/jiaqiong/story-forge>

> 本目录即工程根目录，下文所有相对路径均以 `app/` 为起点。

---

## 1. 技术栈

| 层 | 选型 |
|---|---|
| 前端 | Vue 3 + Vite + TypeScript + Pinia + Vue Router + Element Plus |
| 后端 | Spring Boot 3.5.x / JDK 17+ / **Maven 构建** |
| 持久层 | MyBatis-Plus（含分页、乐观锁、数据权限插件）+ Flyway 迁移 |
| 数据库 | **PostgreSQL 16+ / MySQL 8.0.13+ / SQLite 3.35+（三选一）**，无 Redis / MQ / ES / MinIO |
| 鉴权 | Spring Security + JWT 双令牌（黑名单落库，不依赖 Redis） |
| 限流 | Bucket4j 进程内令牌桶 |
| 部署 | 前后端合并为**单一 Docker 镜像**，`docker compose up` 拉起，入口为 `start.sh` |

---

## 2. 目录结构

```text
app/                                      ★ 工程根目录（后端 + 前端 + 部署 + 文档）
├── README.md                             本文件
├── LICENSE                               GNU AGPL-3.0 协议全文（本项目采用）
├── pom.xml                               Maven 构建入口（单模块）
├── docs/                                 需求 / 详细设计 / 数据库与部署运维
├── script/
│   ├── build.bat|ps1|sh                  编译：前端构建 + 静态合并 + 后端打包（--image 顺便推镜像）
│   ├── start.bat|ps1|sh                  启动：环境变量兜底 + 等 DB + 启动 jar
│   ├── package-win.bat|ps1               Windows 桌面版打包（jpackage）
│   ├── gen-icon.ps1                      生成应用图标
│   ├── db/gen-baseline.mjs               从 PostgreSQL 基线派生 MySQL / SQLite 基线
│   └── deploy/
│       ├── deploy.bat|ps1|sh             远程部署 / 状态 / 日志 / 重启 / psql
│       ├── build.sh                      构建镜像并推送 TCR（skopeo）
│       ├── server.env(.example)          部署目标配置（真实文件不入库）
│       └── remote/                       Dockerfile + docker-compose.yml + nginx 配置模板
├── docker/
│   ├── Dockerfile                        多阶段构建（Node → Maven → JRE）
│   ├── docker-compose.yml                app + postgres + 卷挂载
│   ├── start.sh                          容器入口：等待 DB 就绪后启动服务
│   └── .env.example                      环境变量样例
├── src/main/java/com/storyforge/
│   ├── StoryForgeApplication.java
│   ├── bootstrap/                        启动种子（初始超管、内置灵感技能）
│   ├── common/
│   │   ├── api/                          R / PageResult / PageQuery / ErrorCode
│   │   ├── exception/                    BizException / GlobalExceptionHandler
│   │   ├── context/                      用户上下文 / 数据权限放行 / traceId
│   │   ├── security/                     JWT、认证过滤器、SecurityConfig、令牌黑名单
│   │   ├── ratelimit/                    @RateLimit + 令牌桶 + 切面
│   │   ├── audit/                        @Audit + 审计落库
│   │   └── util/
│   ├── config/                           MyBatis-Plus / 数据权限 / WebMvc / Jackson / 各类配置属性
│   ├── infra/
│   │   ├── ai/                           OpenAiClient / ImageGenClient / LenientJson / prompt/
│   │   ├── db/                           方言探测 + databaseId 映射 + SQLite 时间 TypeHandler
│   │   ├── search/                       搜索引擎客户端
│   │   ├── storage/                      封面本地存储
│   │   └── text/                         汉字统计等
│   ├── modules/
│   │   ├── auth/  user/                  认证、超管用户管理
│   │   ├── ai/  search/                  AI 接口 / 场景绑定 / 提示词模板 / 联网搜索
│   │   ├── novel/  novel/agent/          小说详情、封面、智能助手
│   │   ├── bookshelf/                    书架、设定、章节、人物、势力、物品、事件、分析、导入导出、拆书
│   │   ├── inspiration/                  灵感会话与技能
│   │   └── dashboard/                    仪表盘聚合
│   └── desktop/                          桌面引导（应用根 / 端口 / 单实例 / 开浏览器）
├── src/main/resources/
│   ├── application.yml / -mysql.yml / -sqlite.yml / -dev.yml
│   ├── logback-spring.xml
│   ├── db/migration/{postgresql,mysql,sqlite}/V1__baseline.sql   （MySQL/SQLite 由脚本派生）
│   ├── mapper/*.xml
│   └── static/                           前端构建产物（打进 jar）
└── web/                                  前端源码（与 src 平级的独立工程）
```

---

## 3. 功能范围（全部已交付）

| 模块 | 状态 |
|---|---|
| 标准工程结构（前后端 + 容器化 + 迁移脚本） | ✅ 已完成 |
| 登录 / 刷新令牌 / 登出 / 当前用户 / 修改口令 | ✅ 已完成 |
| 关闭自助注册（`POST /api/auth/register` 固定返回 30030） | ✅ 已完成 |
| 首次登录强制改密（拦截器 + 前端路由守卫） | ✅ 已完成 |
| 超管「用户管理」：列表 / 详情 / 新建 / 编辑 / 启停 / 重置口令 / 删除 / 恢复 | ✅ 已完成 |
| 设置中心「AI 接口管理」：端点增删改查 / 默认端点 / 启停 / 连通性测试 / 模型拉取 / **配置导入导出** | ✅ 已完成 |
| AI 提示词：按 RTCO 结构统一（角色—任务—上下文—输出—约束），设定类要求规则与代价、人物要求欲望与缺陷、正文要求场景推进与反 AI 味；灵感内置技能支持旧版自动升级 | ✅ 已完成 |
| 设置中心「联网搜索」：免密钥搜索源（必应/DuckDuckGo/维基百科）+ SearXNG / Tavily / Serper，默认源、启停、测试与试搜；事件/大纲/正文三场景独立绑定 | ✅ 已完成 |
| 设置中心「场景绑定」：22 个 AI 场景的端点与模型参数覆盖（只登记已有调用的场景，不放占位项；蓝图四段可各绑不同模型） | ✅ 已完成 |
| API Key 加密存储（AES-256-GCM）+ 掩码回显 | ✅ 已完成 |
| 灵感模式：单会话多轮对话（上下文入库）/ 技能选择与编辑（修改仅本人生效）/ 清空上下文 / 一键创建小说 | ✅ 已完成 |
| 书架：卡片墙 / 关键字与状态筛选 / AI 分段生成设定（世界观·背景·简介·冲突·人物，带百分比进度，单段超时 5 分钟）/ 保存开书 / 只建草稿 | ✅ 已完成 |
| 书架「拆书改编」：上传 txt / md 成稿小说 → AI 分段通读（每段约 9000 字、最多 12 段，超长时头/中/尾抽样）拆出主线脉络（一句话内核 / 主题命题 / 分幕 / 关键转折 / 人物功能表 / 结局）→ 按四档改编程度（轻度·中度·重度·完全重构）与可选背景改编方向重建新书：书名、题材、标签、简介、世界观、背景、核心冲突、看点、文风、人物及关系、势力与成员职位、事件池、章节大纲；人物与专名全部脱敏（原文姓名进禁用清单），**不生成正文**；结果可预览、一键保存为新书（含章节-人物 / 章节-事件关联）并保留历史记录 | ✅ 已完成 |
| 页面框架：左侧菜单可折叠（图标模式 / 展开），进入小说工作台自动收起并恢复原状态 | ✅ 已完成 |
| 小说工作台「分析」：**按章节顺序分析**（前面的章节完成才能分析后面的，列表展示标题/字数/大纲贴合/AI 率/状态，点行展开报告）。一次运行并行做两件事——事实同步（本章新出现的人物补进档案、人物/物品/势力的状态变化写入变更履历：章节·原状态·现状态·正文依据、本章关联事件收尾改状态）与质量评审（汉字字数是否达标（目标可设，默认平均章字数）、正文与本章大纲贴合度 0~100 分、AI 率 0~100 分且 0 分为纯 AI 味，本地套话/句首重复/段落节奏统计给模型评分设上限）；报告留档可按章回看，人物/物品/势力卡片提供履历抽屉 | ✅ 已完成 |
| 按分析修改（分析面板 → 展开报告 → 「按分析修改」）：勾选报告里要修的问题（大纲没做到 / 审稿建议 / AI 味信号 / AI 味原句 / 字数问题，可按分组全选），也可直接写自己的修改要求，选修改幅度（轻度/中度/重度）→ AI 只改这些地方并返回新稿 → **左右分栏对比**（段落级对齐 + 段内逐字高亮，删红增绿，可「只看改动」，附改动说明与增删字数）→ 保存后自动重新分析本章。**所有已分析的章节都能改**（不限最后一章）；只生成不落库，未点保存不会动正文 | ✅ 已完成 |
| **AI 封面生成**：AI 接口管理支持**图像接口**（接口类型：文本 / 图像，两类默认端点独立；适配硅基流动、OpenAI 兼容、火山方舟（豆包 Seedream）、智谱 CogView、阿里云百炼（通义万相，异步任务）、魔搭 ModelScope 六类平台，新增时按平台预填地址/模型/尺寸）。书架卡片「⋯」→「生成封面」打开封面管理：按书名/题材/简介自动拼提示词，**封面风格下拉**（动漫 / 真人写实 / CG 3D / 水墨国风 / 油画 / 扁平插画）+ 可补充画面描述与负面提示词 → 生成即落库落盘（`user_id/novel_id/uuid.jpg`，独立卷挂载）→ **封面文字合成**：勾选「书名」「作者名」后由本地字体清晰渲染到封面下方（白字描边 + 底部衬条，超长自动折行缩字号，不用模型画字避免错别字）；文案优先级 = 自定义输入 → 小说书名/笔名 → AI 起名兜底（`COVER_TITLE` 场景，失败静默回退）→ **一部小说最多 5 张**，可设主封面、删除；书架卡片直接展示封面，悬停左右箭头 + 圆点切换（n/5）。取图走登录态接口，不做公开静态路径；删除小说级联清理封面记录与文件 | ✅ 已完成 |
| 小说工作台：点击封面进入，菜单含基本信息（含 5 类设定）、大纲（按卷分章 + AI 生成多章大纲：注入世界观、背景、角色档案、人物关系、事件池与已有章节，每章输出标题、摘要、参与人物与关联事件）、人物（档案 + 关系视图：力导向连线图与关系列表 + AI 按提示语创建角色与关系）、势力（公司/帮派/国家等，成员职位自由填写、一人可多势力任职）、事件（AI 生成、关联参与角色、必要时自动补建角色（单事件≤3）+ 四档状态）、物品（物品描述 + 说明分开记录，状态：活跃/摧毁/废弃/遗失/已消耗，数量可空，AI 按提示语生成）、正文（手动编辑 + AI 写本章：本章大纲与关联事件、参与人物档案、物品势力、上一章结尾 500 字、前 10 章大纲、前 50 章概括，预计字数默认 2500 且只计汉字）/ 删除级联；事件/大纲/正文默认联网检索资料并展示引用来源 | ✅ 已完成 |
| 超管「小说管理」：跨用户只读列表（小说名称、创建账号、创建时间、最后更新时间、状态：创作中/完结/废弃、字数、章节数；支持书名/账号关键字、状态、创建时间筛选与四种排序，可按正常/已删除/全部筛选）+「查看目录」（按卷分组目录、单章大纲与正文按需加载，只读）+ 对**已被用户逻辑删除**的小说执行物理清除（连带章节、要素、封面文件与助手会话，不可恢复；单次批量≤50 本） | ✅ 已完成 |
| 主题切换：浅色 / 深色 / 跟随系统三档，顶栏一键切换；语义 CSS 变量（`--sf-*`）驱动全站，Element Plus 暗色变量联动，首屏内联脚本防白闪，关系连线图双套配色 | ✅ 已完成 |
| 人物属性与背包：角色弹窗新增「属性」「背包」两个页签。属性 = 当前值快照（数值/文本 + 单位，常用属性一键添加），背包三类条目（关联物品档案 / 货币如银两灵石 / 自由文本杂项，含数量、单位、状态：持有·遗失·已消耗）；正文与大纲生成会把角色当前属性、随身物品注入提示词，章节分析会把本章引起的属性与背包变化同时写履历并**更新当前值** | ✅ 已完成 |
| 提示词模板管理（`/settings/prompts`）：按场景定制「指令段」（正文 / 大纲 / 总纲 / 蓝图四段 / 角色 / 物品 / 事件 / 章节分析 / 状态同步 / 一致性检查 / 按分析修改 / 封面 / 要素抽取，共 17 个场景开放），**输出格式由系统统一封装并锁定**，用户改不到 JSON 字段；支持变量芯片插入（`{{novelTitle}}` 等，白名单校验）、示例变量预览（不调 AI）、恢复默认。灵感对话、拆书改编、智能助手、势力生成、封面起名不在此维护，页面上直接不出现 | ✅ 已完成 |
| **提示词模板导入导出**：工具栏「导出」下载 `storyforge-prompt-export` v1 的 JSON 包（默认只导改过的场景，可选全量），「导入」先预览后确认——逐行标出当前已改/默认、将要执行的动作、会被跳过的条数，导入方式三选一（覆盖同名 / 只填空缺 / 清空后导入），逐条回报成功或跳过原因；包格式与版本不符返回 `40072` | ✅ 已完成 |
| 小说导入导出：卡片下拉「导出 JSON」导出整本（19 个分区 + 封面 Base64）；工具栏「导入」在当前账号下重建，旧 ID 全部重映射、重名自动加序号 | ✅ 已完成 |
| **TXT 成品导出**：书架卡片下拉「导出 TXT」，范围可选全书 / 按卷 / 章节区间，输出 UTF-8 BOM 的排版纯文本（卷名行 + 章节标题 + 段落，末尾统计字数）；空章默认跳过，中文文件名走 `Content-Disposition` 的 `filename*` | ✅ 已完成 |
| 章节正文**版本历史与回滚**：每次保存前存一份快照（手动 / AI 生成 / 按分析修改 / 批量连写 / 回滚 / 导入 / 自动），每章最多 20 条滚动清理；可预览任意历史版本并一键回滚（回滚本身也留一条快照，可再回滚回来）；已完成章节禁止外部改正文（回滚不受限） | ✅ 已完成 |
| AI 写本章**流式输出**（SSE）：`meta` → 多个 `delta` → `done`，逐字打字机写入编辑区，可随时停止（停止不落库，已收文本留在编辑区当草稿）；上游不支持流式或只回整包时自动降级为单个 `delta`，前端无感知；同一章节同时只允许一条流 | ✅ 已完成 |
| **批量连写 N 章**：在正文页对某章发起，一次写 1~10 章（可按「卷号 → 章号」顺序往后写，默认跳过已定稿章节且不计入章数），串行逐章生成以保证下一章能读到上一章刚写进去的正文；任务进度可轮询、可中途停止（停止在章与章之间生效，已写的章全部保留），失败策略可选「遇错即停」或「跳过继续」 | ✅ 已完成 |
| **一致性体检**：工作台「体检」页，打开即跑规则层（秒出、零 AI 调用，8 条规则：人物状态 / 物品状态 / 首次登场 / 事件状态 / 章节结构 / 大纲断档 / 档案未同步 / 悬空关联），AI 深检由用户点一次后按 5 章一块滚动异步执行（最多 12 块，块间共用去重集合）。问题落台账，可按批次回看、按状态与严重度筛选，可「忽略」或「标记已修复」；每条问题都带依据与修改建议，规则层给判定数据、AI 层必须引正文原句，定位到章可一键跳转 | ✅ 已完成 |
| **分卷管理**：卷是独立实体（`t_volume`：卷序 / 卷名 / 卷简介），工作台大纲页按卷分组展示，卷头「⋯」可改名、写简介、上移 / 下移换序、删除；章节行可「移动到」其他卷（章号不变）。改卷名会一键同步该卷下所有章节的卷名；删除有章节的卷须先选目标卷并迁移；建章链路自动补卷，存量书启动时幂等回填 | ✅ 已完成 |
| **正文自动保存**：编辑态下停手约 1.5s 自动把正文写库（连续输入最长 20s 必存一次），只改正文与字数、增量修正全书字数；顶栏显示「保存中… / 已自动保存 HH:mm:ss / 保存失败（可点重试）」；`localStorage` 兜底本地草稿，崩溃后再进可恢复；多端冲突返回 `40071`，弹窗可选「用我的覆盖 / 放弃我的改动」；AI 推流与改稿弹窗打开时暂停 | ✅ 已完成 |
| 小说智能助手：工作台自然语言下指令，助手转成结构化操作直接改设定/人物/关系/势力/物品/事件/总纲/章节，支持多轮迭代、任务轮询与取消 | ✅ 已完成 |
| 仪表盘（`/dashboard`）：小说总数与状态分布（草稿 / 连载中 / 已完结 / 已归档）、累计字数与平均每章字数、章节完成度、今日与本月 Token 消耗、近 14 天写作量与 Token 趋势（可切换指标）、近 30 天各场景 AI 消耗占比（超过 6 个场景自动合并「其他」）、最近编辑的 4~5 本书、账号与登录信息（本次 / 上次登录时间与 IP、已使用天数、累计调用次数）；单接口聚合，未登录态的仪表盘不再占用首屏其它请求 | ✅ 已完成 |

---

## 4. 快速开始

> 以下命令均在**工程根目录 `app/`** 下执行。

### 4.1 本地开发

前置：JDK 17+、Maven 3.9+、Node 20+、PostgreSQL 16+（或 MySQL 8.0.13+ / SQLite 3.35+，见 4.1.1）。

```bash
# 1) 准备数据库
createdb storyforge           # 或使用已有实例，按需设置 DB_* 环境变量

# 2) 后端（Flyway 自动建表 + 创建初始超管）
mvn clean package -DskipTests
java -jar target/app.jar      # 默认 profile=dev，端口 8080

# 3) 前端（独立 dev server，接口代理到 8080）
cd web
npm install
npm run dev                   # http://127.0.0.1:5173
```

常用配置（环境变量优先，见 `src/main/resources/application.yml`）：

| 变量 | 说明 | 默认 |
|---|---|---|
| `DB_HOST/DB_PORT/DB_NAME/DB_USER/DB_PASSWORD` | 数据库连接 | `localhost/5432/storyforge/storyforge/storyforge` |
| `STORYFORGE_JWT_SECRET` | JWT 签名密钥（≥ 32 字节） | 开发默认值，**生产必须替换** |
| `STORYFORGE_ADMIN_USERNAME` / `STORYFORGE_ADMIN_PASSWORD` | 初始超管 | `admin` / `Admin@123456` |
| `STORYFORGE_ADMIN_FORCE_CHANGE` | 初始超管首登是否强制改密 | `true` |
| `STORYFORGE_REGISTRATION_ENABLED` | 自助注册开关（需求固定关闭） | `false` |
| `STORYFORGE_FILE_ROOT` | 本地文件根目录 | `./data/files` |
| `STORYFORGE_COVER_ROOT` | 小说封面根目录（路径规则 `user_id/novel_id/{uuid}.jpg`，docker-compose 挂载 `/data/covers`） | `./data/covers` |

### 4.1.1 多数据库支持（PostgreSQL / MySQL / SQLite）

同一份代码支持三种数据库，切换只靠 profile，不需要改代码或重新编译。

| profile | 数据库 | 版本下限 | 典型场景 |
|---|---|---|---|
| 默认（不指定） | PostgreSQL | 16（代码校验 ≥ 12） | 服务端部署、多人共用 |
| `mysql` | MySQL / MariaDB | 8.0.13 / 10.2 | 已有 MySQL 运维体系的部署 |
| `sqlite` | SQLite | 3.35 | 桌面单机版、本地演示 |

```bash
# MySQL：库要提前建好（表结构由 Flyway 创建）
CREATE DATABASE storyforge DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci;
SPRING_PROFILES_ACTIVE=mysql DB_HOST=127.0.0.1 DB_PORT=3306 DB_NAME=storyforge \
DB_USER=root DB_PASSWORD=xxx java -jar target/app.jar

# SQLite：连库文件都不用建，进程起来就有
SPRING_PROFILES_ACTIVE=sqlite SQLITE_PATH=./data/storyforge.db java -jar target/app.jar
```

**方言差异被收敛在三处，业务代码不感知：**

1. **DDL**：`db/migration/{postgresql,mysql,sqlite}/V1__baseline.sql` 三份，由 profile 决定
   `spring.flyway.locations`。MySQL / SQLite 两份**由脚本从 PostgreSQL 版派生，禁止手工修改**：

   ```bash
   node script/db/gen-baseline.mjs          # 改完 PG 基线后重新派生另两份
   node script/db/gen-baseline.mjs --check  # 只校验是否同步（CI 用）
   ```

2. **DML**：Mapper XML 里不出现任何方言语法。需要分支的地方用 MyBatis 的 `_databaseId`
   （值 `postgresql` / `mysql` / `sqlite`），例如 `DashboardMapper.xml` 的 `dateOf` 片段。
   大小写不敏感匹配统一写 `LOWER(col) LIKE LOWER(CONCAT('%', #{kw}, '%'))`——
   PostgreSQL 的 `ILIKE`、MySQL 的 ci collation、SQLite 的 ASCII-only LIKE 在三库上语义一致。

3. **运行时**：`infra/db` 包负责方言探测（`DbDialectProvider`）、MyBatis `databaseId` 映射
   （`DatabaseDialectConfig`）、SQLite 的时间文本读写（`SqliteLocalDateTimeTypeHandler`）。
   分页插件不写死 `DbType`，由 MyBatis-Plus 按 JDBC URL 自动识别。

**新增方言必须在 `DbDialect` 枚举里登记**，否则启动时 `DbDialectProvider` 直接失败——
静默回退会让某一种方言的 SQL 打到另一种库上，故障点漂移到很远的业务逻辑里。

**已知限制：**

- MySQL 不支持部分索引。逻辑删除下的部分唯一索引（`WHERE deleted = 0`）改用「条件函数列」
  实现：`((CASE WHEN deleted = 0 THEN 0 ELSE NULL END))`，语义等价（唯一索引允许 NULL 重复），
  代价是要求 MySQL 8.0.13+；部分**普通**索引直接丢掉 WHERE，仅索引体积略大。
- SQLite 是单写者模型，`application-sqlite.yml` 把连接池压到 4 并把 `busy_timeout` 放宽到 10s。
  高并发写（多用户同时生成正文）不适合 SQLite，桌面单机版足够。
- SQLite 的时间列是 TEXT（`yyyy-MM-dd HH:mm:ss.SSS`），毫秒精度；PostgreSQL 是微秒。
  跨库迁移数据时以毫秒为准。

### 4.2 一键编译与启动（script/）

`script/` 下提供交叉平台的编译与启动脚本，**编译脚本会把前端产物合并进 jar**，所以产物是单端口可运行的一体包（`pom.xml` 没有前端构建插件，手工 `mvn package` 不会带上前端改动）。

| 场景 | 编译 | 启动 |
|---|---|---|
| Windows（cmd） | `script\build.bat` | `script\start.bat` |
| Windows（PowerShell） | `.\script\build.ps1` | `.\script\start.ps1` |
| Linux / macOS / Git Bash | `./script/build.sh` | `./script/start.sh` |

常用参数：编译 `--skip-web`（只打包后端）、`--skip-typecheck`（跳过 vue-tsc）、`--with-tests`、`--no-clean`；启动 `--port 9090`、`--profile prod`、`--no-wait`（跳过数据库等待）。

```bash
./script/build.sh                      # 前端 + 后端，产出 target/app.jar
./script/start.sh                      # 默认 8080，profile=dev
./script/start.sh --port 9090          # 换端口
```

启动脚本在环境变量缺失时补本地开发默认值（`127.0.0.1:5432/storyforge`、`postgres/postgres`、内置开发密钥），并做四件事：校验 jar 与密钥长度、创建 `data/files` 与 `data/covers`、等待数据库就绪、把端口以 `--server.port` 传给 Spring Boot（命令行优先级最高，可避免外部注入的 `SERVER__PORT` 覆盖配置）。

> 编码约定：`.bat` 内部**全部使用 ASCII 提示**（中文 UTF-8 会让 cmd 行偏移错乱，出现 `'d.bat' is not recognized` 这类怪错）；`.ps1` 使用 UTF-8 **带 BOM** 存盘（PowerShell 5.1 无 BOM 时按 GBK 解析会报“字符串缺少终止符”）；`.sh` 为 LF 行尾。

### 4.3 容器部署（单镜像）

```bash
cd docker
cp .env.example .env
# 修改 .env：至少替换 STORYFORGE_JWT_SECRET 与 DB_PASSWORD
docker compose up -d --build
# 访问 http://localhost:8080
```

数据落在 `docker/data/`：`files/`（业务文件）、`pg/`（数据库）、`logs/`（应用日志）。

### 4.3.1 Windows 桌面版（EXE 安装包 / 绿色版）

桌面版把整套应用（含 JDK17 运行时）打成**免 Java、免数据库**的可执行产物，
数据库就是一个 SQLite 文件，适合本机单人创作。两种形态：

| 形态 | 产物 | 数据位置 | 适用 |
|---|---|---|---|
| 安装包 | `dist\StoryForge-<版本>.exe` | `%APPDATA%\StoryForge\data` | 正式发布给最终用户 |
| 绿色版 | `dist\StoryForge\` 目录 | exe 同级 `data\` | 便携、U 盘、免安装 |

```powershell
# 生成图标（首次或改了图标后）
.\script\gen-icon.ps1

# 安装包（正式发布）：需要 WiX Toolset 3.x 提供 candle.exe / light.exe
.\script\package-win.ps1 -Type exe -NoConsole -Version 1.0.0

# 绿色版（默认）
.\script\package-win.ps1
```

| 参数 | 说明 |
|---|---|
| `-Type exe\|app-image` | `exe` 出安装包，`app-image`（默认）出绿色目录 |
| `-Version 1.0.0` | 版本号，必须纯数字三段式（jpackage 拒绝 `-SNAPSHOT`） |
| `-NoConsole` | 不显示控制台窗口（日志仍落 `data\logs\`）；正式发布建议加 |
| `-WixDir` | WiX 3.x 目录（含 `candle.exe`）；不填则自动探测 |
| `-UpgradeUuid` | 安装包升级标识；同一应用的所有版本必须一致，升级才能覆盖安装 |
| `-SkipBuild` | 复用已有 `target\app.jar`，跳过前端 + Maven |
| `-SkipWeb` | 跳过前端构建，只重新 Maven 打包 |

**安装包（`-Type exe`）**：当前用户安装、免管理员，可选安装目录，自动创建开始菜单项与桌面快捷方式，
可从「应用和功能」卸载。数据刻意放在 `%APPDATA%\StoryForge\data`——jpackage 的 per-user
安装目录正是 `%LOCALAPPDATA%\StoryForge`，数据若同处会被卸载程序一并删除（已实测）。
备份该目录即完成迁移，删除它等于重置。

**绿色版产物结构：**

```
dist\StoryForge\
├── StoryForge.exe      ← 双击入口
├── app\app.jar         ← 后端 + 前端（单 jar）
├── runtime\            ← 自带精简 JDK17（约 90MB）
└── data\               ← 首次启动自动生成
    ├── storyforge.db   ← SQLite 主库（删目录即重置）
    ├── covers\ files\  ← 封面 / 文件
    └── .instance.lock  ← 单实例锁
```

**运行行为（两种形态一致）：**

- 双击 exe → 自动选端口（8080 被占则顺延到 8089）→ 起服务 → 打开浏览器
  `http://127.0.0.1:8080`。
- **单实例**：再启动一次不会起第二个进程，而是直接打开已运行的实例（读 `data\.instance.port`）。
- 数据根：绿色版由 exe 路径推导（`jpackage.app-path`），**与当前工作目录无关**；
  安装版取 `%APPDATA%\StoryForge\data`（打包时传 `-Dstoryforge.storage=user`），
  也可用 `-Dstoryforge.home=<目录>` 自定义。
- 默认账号 `admin / Admin@123456`，首次登录强制改密。

> 桌面模式判定：被打包（存在 `jpackage.app-path`）或 profile 含 `sqlite` 时，启动引导
> （`com.storyforge.desktop` 包）才会接管端口/锁/开浏览器；PG/MySQL 服务器模式完全不受影响。

### 4.3.2 远程服务器部署（SSH → Docker Compose）

`script/deploy/` 提供一键部署到远程 Linux 服务器的能力（应用 + PostgreSQL 双容器，
数据落远端 `/opt/storyforge/data/`）。**部署不编译代码**：应用镜像直接取自腾讯云 TCR
（`ccr.ccs.tencentyun.com/jiaqiong/story-forge:v1.0.0`），服务器上只有
`docker-compose.yml` + `.env` 两个文件，没有源码、没有 jar、不需要 JDK。

连接参数在 `script/deploy/server.env`（HOST / USER / KEY / REMOTE_DIR / APP_PORT / JAVA_OPTS），
换服务器或密钥只改这一个文件。

```bash
# Linux / macOS / Git Bash（工作目录 app/）
./script/deploy/deploy.sh                # 拉镜像 → 启动 → 健康检查（默认）
./script/deploy/deploy.sh --image ccr.ccs.tencentyun.com/jiaqiong/story-forge:v1.0.1
./script/deploy/deploy.sh --from-source  # 从源码构建（需 JDK/Maven/Node，仅离线兜底）
./script/deploy/deploy.sh status         # 容器状态 + 健康 + 磁盘/内存
./script/deploy/deploy.sh logs 300       # 应用日志
./script/deploy/deploy.sh restart        # 重启应用容器
./script/deploy/deploy.sh stop           # 停止（数据保留）
./script/deploy/deploy.sh psql           # 进远端 PostgreSQL

# Windows PowerShell
.\script\deploy\deploy.ps1               # 参数同上（-Image、-FromSource、logs -Lines 300 等）
.\script\deploy\deploy.bat               # cmd 薄封装
```

**行为约定：**

- **默认只拉镜像**：上传 `docker-compose.yml` → 远端 `compose pull app` → `up -d --no-build`。
  升级等于改镜像标签（`--image`，或改 `remote/docker-compose.yml` 的默认值）再跑一次。
- **compose 没有 `build:` 段**：`remote/docker-compose.yml` 的 `image` 默认写死 TCR 地址，
  现场构建能力挪到 `remote/docker-compose.build.yml`，只有 `--from-source` 才叠加它。
- **`.env` 只在首次部署生成**（openssl 随机 JWT/加密主密钥/DB 密码/管理员密码，口令仅打印一次）；
  再次部署保留远端 `.env` 不覆盖——加密主密钥更换会导致已存 API Key 密文无法解密。
- **2C2G 内存调优**：JVM `-Xms128m -Xmx640m`、PG `shared_buffers=128MB`（`script/deploy/remote/docker-compose.yml`），
  不要随意调大，会 OOM。
- 首次部署自动配置腾讯云内网 Docker 镜像加速（`mirror.ccs.tencentyun.com`）。
- **仓库为私有**时先在服务器 `docker login ccr.ccs.tencentyun.com`；当前仓库为公开可直接匿名拉取。
- **公网访问不通 ≠ 部署失败**：先 `deploy.sh status` 看 health；health=UP 说明是云控制台防火墙
  未放行对应 TCP 端口，需在轻量服务器控制台「防火墙」中放行。
- 同款能力已封装为 WorkBuddy 技能 `.workbuddy/skills/storyforge-deploy/`，
  对话说「部署到服务器 / 看服务器日志」即可触发。

### 4.3.3 镜像构建与推送（腾讯云 TCR）

`script/deploy/build.sh` 把工程打成容器镜像并推送到 TCR 个人版
（当前地址 `ccr.ccs.tencentyun.com/jiaqiong/story-forge`，命名空间/仓库在 TCR 控制台创建）。

```bash
# Linux / macOS / Git Bash（工作目录 app/）
./script/deploy/build.sh                     # 打包 jar → 构建镜像 → 推送 ${IMAGE_TAG}
./script/deploy/build.sh --skip-jar          # 复用 target/app.jar（不重新打包）
./script/deploy/build.sh --tag v1.0.1        # 指定版本标签
./script/deploy/build.sh --also-latest       # 额外推一份 latest
./script/deploy/build.sh --no-push           # 只构建不推送
./script/deploy/build.sh --local             # 在本机 Docker 构建（默认在服务器构建）
./script/deploy/build.sh login               # 在服务器上登录 TCR

# 打包 + 推镜像一条命令
./script/build.sh --image

# 从镜像仓库部署（默认行为，不上传 jar，远端只 pull + up）
./script/deploy/deploy.sh
```

**行为约定：**

- **默认在服务器上构建推送**：镜像层缓存与 registry 登录态都在服务器，本机无需装 Docker。
  构建上下文只有 `app.jar + Dockerfile + start.sh`，上传到 `/opt/storyforge/image-build/`，
  构建耗时几秒（不下载依赖、不跑 Maven）。
- **推送优先用 `skopeo copy`**：TCR 对较大层用 `docker push` 容易出现
  `net/http: timeout awaiting response headers`；skopeo 为分块上传且自带重试
  （服务器 `dnf install -y skopeo` 安装）。没有 skopeo 时退回 `docker push`（自动重试 3 次）。
- **凭据不落盘**：`build.sh login` 从 `secret/tcr-password.txt` 或 `TCR_PASSWORD` 环境变量读密码；
  `server.env` 只存 `REGISTRY / IMAGE_NAME / IMAGE_TAG / REGISTRY_USER`（账号 ID）。
- **compose 不再带 `build:`**：`remote/docker-compose.yml` 的
  `image: ${APP_IMAGE:-ccr.ccs.tencentyun.com/jiaqiong/story-forge:v1.0.0}` 是唯一来源，
  部署即拉镜像；源码构建走 `remote/docker-compose.build.yml` 叠加（`--from-source`）。
- **镜像标签带 OCI 元数据**：`org.opencontainers.image.version/created/revision`，
  对应 `--build-arg APP_VERSION/BUILD_DATE/VCS_REF`，便于在 TCR 控制台识别版本。
- 服务器 `daemon.json` 已加 `"max-concurrent-uploads": 1`（缓解 TCR 大层上传超时）。

### 4.4 首次登录

1. 使用 `admin / Admin@123456`（或 `.env` 中的值）登录。
2. 因为 `force-change-password=true`，登录后会被强制跳转到「修改口令」页；
   在此页面之前，后端仅放行 `/api/auth/me`、`/api/auth/password`、`/api/auth/logout`。
3. 改密成功后进入仪表盘，超管可见「用户管理」「小说管理」两个平台菜单。

> 若希望初始超管直接可用，可设置 `STORYFORGE_ADMIN_FORCE_CHANGE=false`。

---

## 5. 构建与验证记录

已在本机完成的验证（均在 `app/` 目录下执行）：

| 项 | 命令 | 结果 |
|---|---|---|
| 后端编译 | `mvn -DskipTests compile` | ✅ 62 个源文件编译通过（JDK 17，release=17） |
| 后端打包 | `mvn -DskipTests clean package` | ✅ 产出可执行 `target/app.jar`（Spring Boot 3.5.6 repackage） |
| 前端类型检查 | `npx vue-tsc --noEmit` | ✅ 0 错误 |
| 前端构建 | `npm run build` | ✅ 1691 个模块，产出 `web/dist` |
| 应用上下文启动 | `java -jar target/app.jar` | ✅ 12s 启动成功，Tomcat 就绪 |
| Mapper 一致性 | XML statement id ↔ 接口方法 | ✅ 4 个语句、1 个 `<sql>` 片段全部对齐 |
| 未认证访问 | `GET /api/auth/me` | ✅ `401 {"code":20001}` |
| 权限隔离 | `GET /api/admin/users`（无令牌） | ✅ `401`（`/api/admin/**` 仅超管） |
| 无效令牌 | `Authorization: Bearer invalid...` | ✅ `401 {"code":20001}`，JWT 过滤器生效 |
| SPA 回退排除 | `GET /not-exist-page` | ✅ `404 {"code":10002}`（未落到 index.html） |
| 参数校验 | 空登录名/口令 | ✅ `400 {"code":10001}`，带字段级提示 |
| 注册关闭 | `POST /api/auth/register` | ✅ `403 {"code":30030}` |
| 登录限流 | 连续请求 `POST /api/auth/login` | ✅ 超过 10 次/分钟后返回 `429 {"code":90002}` |
| 接口文档 | `/v3/api-docs`、`/swagger-ui/index.html` | ✅ 正常生成与访问 |
| 三库基线语法 | PostgreSQL 18.3 / MySQL 9.2.0 / SQLite 3.47 | ✅ 均为 33 表 / 109 索引 / 67 CHECK，逐项一致 |
| MySQL 部分唯一索引等价性 | 同名 `deleted=0` 二次插入 | ✅ `ERROR 1062 Duplicate entry`；逻辑删除后可重建同名（与 PostgreSQL 行为一致） |
| SQLite profile 启动 | `SPRING_PROFILES_ACTIVE=sqlite` | ✅ Flyway 建 33 表，登录 / 仪表盘 / 用户列表 / 建书全部 `code=0`，时间读写精确到毫秒 |
| MySQL profile 启动 | `SPRING_PROFILES_ACTIVE=mysql` | ✅ 同上全通，时间字段 `2026-10-23T13:58:52.507` 读回无偏差 |
| PostgreSQL 回归 | 现有 `storyforge` 库 | ✅ `Successfully validated` + `No migration necessary`；4 用户 / 3 小说 / 17 章 / 125 用量 / 7 AI 配置数量未变 |

> ✅ **端到端验证已完成**（2026-10-16，本机 PostgreSQL 18，库 `storyforge`）：
> Flyway 建表（4 张）→ 种子超管 → 登录签发双令牌 → 首登强制改密 → 用户管理全流程，
> 共 **51 项断言全部通过**；过程中发现并修复 2 个缺陷（见 §8「已修复问题」）。
>
> ✅ **AI 接口管理端到端验证已完成**（2026-10-19，V2 迁移 → 6 张表；用本地 mock OpenAI 服务跑通真实 HTTP 调用）：
> 端点 CRUD / 密文与掩码 / 连通性测试（成功与 401 失败两条路径）/ 模型拉取与缓存 / 默认端点切换与补位 /
> 场景绑定 18 项 / 跨用户隔离 / 越权拦截，共 **63 项断言全部通过**，0 失败。
>
> ✅ **三期功能（主题 / 属性背包 / 提示词模板）验证已完成**（2026-10-21，本机 PostgreSQL 18，Flyway V16~V19）：
> 属性与背包 8 个接口（含重名 `40043`、重复挂载 `40045`、清空数量、状态切换）、提示词模板 5 个接口
> （非法变量 `40005`、不支持模板的场景 `40049`、保存-预览-恢复默认闭环、格式契约确实追加在末尾），
> 共 **24 项断言通过**；页面侧用无头 Edge + CDP 截图验收了浅色/深色主题、属性页签、背包页签。
>
> ✅ **场景收敛与模板接入修复**（2026-10-21，V18 + V19）：删掉 12 个「只有枚举、没有调用方」的占位场景
> （否则绑了永不生效，还会因「该接口已被场景绑定」挡住 AI 接口删除）；蓝图由 `NOVEL_BLUEPRINT` 拆成四段独立场景码；
> 修复角色 / 物品 / 事件三个「标为可编辑但根本没读模板」的场景。验证方式：起本地 mock OpenAI 服务记录收到的
> system message，确认自定义内容确实进了请求（含变量替换、契约追加），并验证蓝图四段可独立绑模型
> （人物段走 `mock-model-2`，其余三段走 `mock-model`）。临时账号与数据已清理。
>
> ✅ **正文查看态与定稿锁定**（2026-10-23，无新迁移）：工作台「正文」默认只读，点「编辑」才可改；章节状态为
> 「已完成」（`DONE`）时前端禁用编辑与 AI 写本章，后端 `updateChapter` 兜底拒绝正文 / 标题变更（`40061`）。
> 验证（真实章节，测后已还原）：置 `DONE` → 原样保存正文放行、改正文与改标题均 `400` + `40061`、
> 改回 `DRAFT` 后可保存、恢复 `WRITING` 后正文长度 2220 / 1847 汉字与测试前一致。
>
> ✅ **迁移脚本合并为单基线**（2026-10-23，V1~V26 → `V1__baseline.sql`）：项目只有一个库、从未发布中间版本，
> 26 步增量无保留价值。用 `pg_dump --schema-only` 导出终态（`--no-owner --no-privileges`，排除 `flyway_schema_history`），
> 剔除 PG 18 新增的 `\restrict` / `\unrestrict` 元命令后生成 2781 行基线。
> 验证：在临时库 `storyforge_baseline_test` 执行基线后与源库做四维比对——**33 表 / 526 列 / 109 索引 / 441 约束完全一致**；
> 现有库删掉 `flyway_schema_history` 后重启，日志为 `baselined schema with version: 1` + `No migration necessary`，
> 数据零变化（4 用户 / 3 小说 / 17 章 / 125 条用量），接口回归正常。全库备份见 `app/data/backup/`。
>
> ✅ **多数据库方言适配**（2026-10-23，无新迁移）：PostgreSQL 专有语法全部清除——`ILIKE` 改为
> `LOWER(col) LIKE LOWER(CONCAT(...))`、`INTERVAL '1 day'` 改为 Java 侧算开区间上界、`now()` 改为调用方传参、
> `CAST(... AS DATE)` 收进 `dateOf` 片段按 `_databaseId` 分支；分页插件不再写死 `DbType.POSTGRE_SQL`。
> 基线拆成 `db/migration/{postgresql,mysql,sqlite}/` 三份，后两份由 `script/db/gen-baseline.mjs` 派生。
> 验证：三库各自起独立实例跑 Flyway + 登录 + 仪表盘 + 用户列表 + 超管小说列表 + 建书，
> 结果均为 33 表 / 109 索引 / 67 CHECK、接口 `code=0`；MySQL 的部分唯一索引用「条件函数列」实现后，
> 逐条验证了「未删除同名冲突 / 逻辑删除后可重建同名」与 PostgreSQL 语义一致；PostgreSQL 现有库零迁移、数据零变化。
> 临时 MySQL 实例（3307）、SQLite 库文件与其 218MB 数据目录已全部清理。
>
> ✅ **仪表盘与 AI 用量统计验证已完成**（2026-10-21，V20 → `t_ai_usage` + `t_user.prev_login_*`）：
> `GET /api/dashboard/overview` 返回结构完整（小说状态分布 4/2/1/0、累计字数 183,790、章节 11、今日 Token 39,408、
> 14 天趋势补齐 0 值日期、7 个场景按 token 降序且占比合计 100%）；用量埋点用本地 mock OpenAI 跑通两条路径——
> 成功调用写入 `INSPIRATION_CHAT / mock-model / 10+10=20 token / success=1`，把 baseUrl 指向关闭端口后
> 写入 `success=0` + 失败原因；登录后 `prev_login_*` 正确承接上一次的 `last_login_*`（首次登录为空）。
> 页面侧用无头 Edge + CDP 截图验收了有数据、空状态、深色三套渲染。临时账号（`qa_dash` / `qa_empty`）及其全部数据已物理清理。
>
> ✅ **按分析修改验证已完成**（2026-10-21，`CHAPTER_REVISE` 场景 + `POST /chapters/{id}/revise`）：
> 用本地 mock OpenAI 跑通「选问题 → 生成 → 对比 → 保存 → 自动重新分析」全链路。接口层确认：
> 3 条勾选问题 + 自定义要求确实进了 user prompt（分组编号、幅度说明、原文都在），system 里是
> 模板指令段 + 反 AI 味块 + 锁定的 JSON 契约；返回 `419 → 483 字`、`notes` 两条，且模型在 JSON 字符串里
> 写了真实换行，`LenientJson` 修复后正常解析。边界验证：未分析的章节返回 `40051`、非最后一章（第 2 章）可正常改稿、
> 模型返回原文时接口原样返回且前端「保存」按钮置灰。页面侧用无头 Edge + CDP 截图验收了选择页、左右对比页、
> 无改动空状态、以及保存后自动重新分析（大纲贴合 62→86、AI 率 38→72）。临时账号 `qa_revise` 及其全部数据已物理清理。

在本机 PostgreSQL 上启动（超级用户 `postgres`，口令 `postgres`）：

```bash
# 1) 建库（仅首次）
PGPASSWORD=postgres psql -h 127.0.0.1 -U postgres \
  -c "CREATE DATABASE storyforge WITH ENCODING 'UTF8' TEMPLATE template0;"

# 2) 打包并启动（Flyway 自动建表 + 创建初始超管）
mvn -DskipTests clean package
DB_HOST=127.0.0.1 DB_PORT=5432 DB_NAME=storyforge DB_USER=postgres DB_PASSWORD=postgres \
STORYFORGE_JWT_SECRET=storyforge-local-dev-secret-key-32bytes-minimum-2026 \
STORYFORGE_SECRET_KEY=storyforge-local-crypto-secret-2026 \
java -jar target/app.jar

# 3) 浏览器访问 http://127.0.0.1:8080 （前端已打进 jar，单端口）
#    admin / Admin@123456 → 首登强制改密
```

> 单端口访问要求 `src/main/resources/static` 里有前端产物：
> `cd web && npm run build && cp -r dist/. ../src/main/resources/static/`，再执行第 2 步的打包。

---

## 6. 接口清单

统一响应体：`{ "code": 0, "message": "ok", "data": {}, "traceId": "..." }`（`code=0` 为成功）。
接口文档：`/swagger-ui.html`、`/v3/api-docs`（可导入 Apifox）。

### 6.1 认证 `/api/auth`

| 方法 | 路径 | 说明 | 限流 |
|---|---|---|---|
| POST | `/login` | 账密登录，返回双令牌 | IP 10 次/分钟 |
| POST | `/refresh` | 刷新令牌（一次性轮换，旧的立即失效） | — |
| POST | `/logout` | 登出，吊销当前访问令牌与传入的刷新令牌 | — |
| GET | `/me` | 当前登录用户 | — |
| POST | `/password` | 修改本人登录口令 | USER 10 次/分钟 |
| POST | `/register` | 自助注册（固定返回 `30030`） | — |

### 6.2 超管用户管理 `/api/admin/users`（需 `SUPER_ADMIN`）

| 方法 | 路径 | 说明 |
|---|---|---|
| GET | `` | 列表：`page/size/keyword/role/status/includeDeleted` |
| GET | `/{id}` | 详情 |
| POST | `` | 创建账号（唯一开号路径，首登强制改密） |
| PUT | `/{id}` | 编辑昵称 / 角色 / 备注 |
| PUT | `/{id}/status` | 启用 / 停用 |
| PUT | `/{id}/password` | 重置口令（重置后下次登录必须改密） |
| DELETE | `/{id}` | 逻辑删除（保留 30 天可恢复） |
| POST | `/{id}/restore` | 恢复已删除账号 |

读接口 600 次/分钟/用户，写接口 120 次/分钟/用户（进程内令牌桶）。

### 6.2.1 超管小说管理 `/api/admin/novels`（需 `SUPER_ADMIN`，只读 + 物理清除）

| 方法 | 路径 | 说明 |
|---|---|---|
| GET | `` | 全平台小说分页：`page/size/keyword/state/scope/createdFrom/createdTo/sort`（`keyword` 匹配书名/账号/昵称，`state` 取 CREATING / COMPLETED / ABANDONED，`scope` 取 NORMAL / DELETED / ALL，`sort` 取 CREATED_DESC / UPDATED_DESC / WORDS_DESC / CHAPTERS_DESC） |
| GET | `/{id}` | 小说基本信息 + 章节目录（不含正文） |
| GET | `/{id}/chapters/{chapterId}` | 单章详情（正文、大纲摘要、字数） |
| DELETE | `/{id}` | **物理清除**：仅对已逻辑删除的小说有效，连带删除章节、要素、封面文件与助手会话，不可恢复 |
| POST | `/purge` | 批量物理清除，单次最多 50 本，逐本返回结果 |

不提供修改他人创作内容的接口；跨用户读取通过 `DataPermissionContext.withoutIsolation(...)` 在单次查询内放行 `user_id` 隔离。物理清除记审计 `ADMIN_NOVEL_PURGE` / `ADMIN_NOVEL_PURGE_BATCH`。

### 6.3 AI 接口管理 `/api/ai-configs`（登录用户，按用户隔离）

| 方法 | 路径 | 说明 |
|---|---|---|
| GET | `` | 列表，默认端点排在最前；API Key 只返回掩码 |
| GET | `/{id}` | 详情（含最近一次拉取的模型列表缓存） |
| POST | `` | 新增：`name/baseUrl/apiKey/defaultModel` + 可选 `kind`（TEXT/IMAGE，默认 TEXT）、`provider`、`imageSize`、`timeoutMs/temperature/maxTokens/topP/extraHeaders/enabled/isDefault` |
| PUT | `/{id}` | 编辑，字段为 `null` 不修改；`apiKey` 留空表示保留原密钥 |
| DELETE | `/{id}` | 删除（被场景绑定时返回 `40010`） |
| PUT | `/{id}/default` | 设为默认端点（停用的接口返回 `40009`） |
| PUT | `/{id}/status` | 启用 / 停用；停用默认端点会同时取消默认标记 |
| POST | `/{id}/test` | 连通性测试，返回 `{ok, latencyMs, model, reply, totalTokens, error}` |
| GET | `/{id}/models` | 拉取 `{baseUrl}/models` 并缓存 |
| GET | `/export?includeApiKey=true` | **导出全部接口配置**为 JSON 附件（`storyforge-ai-config-export` v1）；含明文密钥时文件等同于凭据 |
| POST | `/import` | **导入接口配置**：逐条校验、单条失败只跳过该条；重名自动追加「（2）」；密钥用本实例主密钥重新加密；不抢占已有默认端点 |

约定：`baseUrl` 需包含版本段（如 `https://api.openai.com/v1`），系统在其后追加 `/chat/completions` 与 `/models`。
API Key 以 **AES-256-GCM** 加密入库（`v1:base64(iv|ct)`），主密钥来自 `STORYFORGE_SECRET_KEY`。

导入导出的边界：

- 搬运 `name/kind/provider/imageSize/baseUrl/apiKey/defaultModel/modelList/timeoutMs/temperature/maxTokens/topP/extraHeaders/enabled/isDefault/remark`；
- **不搬运** `id`、创建/更新时间、最近测试结果（都是本实例的运行痕迹）；
- 密钥密文绑定站点主密钥，跨站点无法解密，因此导出的是解密后的明文，导入时用目标站点密钥重新加密；
- `includeApiKey=false` 导出的包不含密钥，导入后这些接口显示「未配置密钥」，需编辑补填；
- 单次导入上限 200 条，限流 10 次/分钟。

### 6.4 场景绑定 `/api/scene-bindings`（登录用户，按用户隔离）

| 方法 | 路径 | 说明 |
|---|---|---|
| GET | `` | 22 个 AI 场景及当前绑定（未绑定字段为空）；文本场景只能绑文本接口、封面场景只能绑图像接口（错绑 `40057`） |
| PUT | `` | 批量保存：`{"items":[{"code":"BODY_GEN","aiConfigId":1,"model":"gpt-4o","temperature":0.9,"maxTokens":800}]}`；`aiConfigId` 为 `null` 表示清除绑定、回落到默认端点 |

### 6.5 人物属性与背包 `/api/bookshelf`（登录用户，按用户隔离）

| 方法 | 路径 | 说明 |
|---|---|---|
| GET | `/characters/{characterId}/attributes` | 属性列表（按 `sortNo`） |
| POST | `/characters/{characterId}/attributes` | 新增：`name/value/unit/attrType(NUMBER/TEXT)/note/sortNo`；同名 `40043` |
| PUT | `/attributes/{id}` | 修改（可改名与类型） |
| DELETE | `/attributes/{id}` | 删除 |
| GET | `/characters/{characterId}/inventory` | 背包（物品类回填 `itemDescription` / `itemOrigin`） |
| POST | `/characters/{characterId}/inventory` | 新增：`entryType(ITEM/CURRENCY/MISC)/itemId/name/quantity/unit/status/note`；重复 `40045`、物品不存在 `40047` |
| PUT | `/inventory/{id}` | 修改；`clearQuantity=true` 表示清空数量（`updateById` 会跳过 null） |
| DELETE | `/inventory/{id}` | 删除 |

### 6.6 提示词模板 `/api/prompt-templates`（登录用户，按用户隔离）

| 方法 | 路径 | 说明 |
|---|---|---|
| GET | `` | 场景列表：`scene/displayName/group/editable/overridden/variableCount/updatedAt` |
| GET | `/{scene}` | 详情：当前内容、默认内容、**锁定的输出格式**、可用变量、说明 |
| PUT | `/{scene}` | 保存指令段（≤8000 字，变量须在白名单内，否则 `40005`） |
| DELETE | `/{scene}` | 恢复默认（逻辑删除覆盖行） |
| POST | `/{scene}/preview` | 用示例变量渲染完整提示词，**不调用 AI** |
| GET | `/export?onlyOverridden=` | 导出 JSON 包（`storyforge-prompt-export` v1，附件下载；默认只导改过的） |
| POST | `/import?mode=` | 导入模板包，`mode` ∈ `MERGE`（覆盖同名，默认）/ `SKIP_EXISTING`（只填空缺）/ `REPLACE`（清空后导入）；包 `format`/`formatVersion` 不符返回 `40072`，逐条回报成功/跳过与原因 |

开放编辑的场景（17 个）：`BODY_GEN`、`OUTLINE_GEN`、`MASTER_OUTLINE`、`BLUEPRINT_WORLDVIEW`、`BLUEPRINT_BACKGROUND`、
`BLUEPRINT_CONFLICT`、`BLUEPRINT_CHARACTER`、`BLUEPRINT_QUESTIONS`、`CHARACTER_BUILD`、`ITEM_BUILD`、`EVENT_SUPPLY`、
`ANALYSIS_GEN`、`SYNC_EXTRACT`、`CONSISTENCY_CHECK`、`CHAPTER_REVISE`、`COVER_IMAGE`、`NOVEL_EXTRACT`；
灵感对话（`INSPIRATION_CHAT`）、拆书改编（`DECONSTRUCT`）、小说智能助手（`NOVEL_AGENT`）、势力生成（`FACTION_BUILD`）、
封面起名（`COVER_TITLE`）不出现在列表中，直接访问返回 `40049`。
最终 system 提示 = 用户指令段 + 系统追加的输出格式契约，后者不入库、不可改。

### 6.7 仪表盘 `/api/dashboard`（登录用户，按用户隔离）

| 方法 | 路径 | 说明 |
|---|---|---|
| GET | `/overview` | 首屏总览：账号与登录信息、小说状态分布、字数与章节统计、AI 用量、14 天趋势、30 天场景分布、最近编辑的小说 |

返回结构（`data`）：

| 字段 | 内容 |
|---|---|
| `profile` | `username/nickname/roleLabel/registeredAt/usedDays`、`lastLoginAt+Ip`（本次）、`prevLoginAt+Ip`（上次） |
| `novel` | `total/draft/writing/finished/archived`、`wordCount`、`chapterCount`、`avgWordsPerChapter` |
| `chapter` | `total/done/writing/draft` |
| `ai` | `todayTokens/todayCalls/todayPromptTokens/todayCompletionTokens/todayFailed`、`monthTokens/monthCalls`、`totalTokens/totalCalls`、`lastCalledAt` |
| `trend[]` | 近 14 天，按日期升序：`day/label/words/chapters/tokens/calls`，无数据的日期补 0 |
| `scenes[]` | 近 30 天各场景：`scene/label/group/calls/tokens/percent`，按 token 降序 |
| `recentNovels[]` | 最近编辑 5 本：`id/title/genre/status/statusLabel/chapterCount/wordCount/updatedAt` |

统计口径见 `docs/02-详细设计说明书.md` §3.9（仪表盘）；Token 全部来自 `t_ai_usage`（每次模型调用后由 `AiUsageService` 写入，失败也记一行）。

### 6.8 章节分析与按分析修改 `/api/bookshelf`（登录用户，按用户隔离）

| 方法 | 路径 | 说明 |
|---|---|---|
| POST | `/chapters/{id}/analysis` | 分析本章（按章节顺序校验）；同步新人物、变更履历、事件状态 |
| POST | `/chapters/{id}/revise` | **按分析修改**：传入勾选的问题、自定义要求、修改幅度与目标字数，返回原文 + 新稿，**不落库** |
| GET | `/novels/{id}/analysis-overview` | 分析面板列表：每章最近一次结果与状态 |
| GET | `/chapters/{id}/analyses` | 本章分析历史（最近 20 次） |
| GET | `/analyses/{id}` | 报告详情 |
| GET | `/novels/{id}/change-histories` | 要素变更履历（可按类型 / 实体过滤） |

`revise` 入参：`analysisId`（可空）、`items[]{type,text,detail}`（type ∈ `OUTLINE_MISS`/`OUTLINE_SUGGESTION`/`AI_SIGNAL`/`AI_EXAMPLE`/`LENGTH`，最多 24 条）、`instruction`（≤2000 字）、`intensity`（`LIGHT`/`MEDIUM`/`HEAVY`）、`targetWords`。
返回：`originalContent`、`revisedContent`、`notes[]`（AI 自述改动要点）、`wordCountBefore/After`、`model`、`latencyMs`。
未分析过的章节返回 `40051`，生成不出可用新稿返回 `40052`；保存仍走 `PUT /chapters/{id}`，保存后由前端触发一次重新分析。

### 6.9 小说封面 `/api/novels`（登录用户，按用户隔离）

| 方法 | 路径 | 说明 |
|---|---|---|
| GET | `/{novelId}/covers` | 封面列表（主封面在前，其余按切换顺序） |
| POST | `/{novelId}/covers/generate` | **生成封面**：按书名/题材/简介拼提示词，`prompt`（画面描述，可空）、`negativePrompt`、`imageSize`、`style`（ANIME/REAL/CG/INK/OIL/FLAT）、`withTitle/withAuthor`（文字开关）、`textRenderMode`（`OVERLAY` 后期本地字体叠加（默认）/ `AI_NATIVE` 模型直接画出书名作者）、`titleText/authorText`（自定义文案）可选；无论哪种方式，简介/标签等文字均禁止出现在画面中；生成即落库落盘，上限 5 张（`40056`），限流 6 次/分钟 |
| PUT | `/covers/{coverId}/primary` | 设为主封面 |
| DELETE | `/covers/{coverId}` | 删除封面（记录逻辑删除 + 文件物理删除，剩余封面重排序号） |
| GET | `/covers/{coverId}/image` | 取封面图片（需登录态，7 天 private 缓存） |

图像接口解析走 `ImageEndpointResolver`（场景绑定 → 图像类默认端点 → 首个启用图像接口，`40053`）；平台协议适配与存储规则见设计说明书 §3.27。小说列表 / 详情 VO 携带 `covers[]`，删除小说级联清理封面记录与整个 `novel_id` 目录。

---

## 7. 错误码

| 段 | 含义 | 已使用 |
|---|---|---|
| `0` | 成功 | — |
| `1xxxx` | 通用 | `10001` 参数校验失败、`10002` 资源不存在、`10003` 方法不支持 |
| `2xxxx` | 认证与权限 | `20001` 未登录/令牌失效、`20002` 用户名或密码错误、`20003` 账号已停用、`20004` 需先修改初始口令、`20005` 原口令不正确、`20006` 无权限 |
| `3xxxx` | 业务规则 | `30001` 用户名已存在、`30002` 用户名属于已删除账号、`30003` 用户不存在、`30030` 注册已关闭、`30032` 不能对自己执行该操作、`30033` 至少保留一个启用的超管 |
| `4xxxx` | 创作域 / 设置中心 | `40001`~`40005` 创作域预留；`40006` AI 配置不存在、`40007` 接口名称已存在、`40008` 场景编码非法、`40009` 接口已停用、`40010` 接口被场景绑定、`40043` 属性已存在、`40044` 属性不存在、`40045` 背包条目已存在、`40046` 背包条目不存在、`40047` 关联物品不存在、`40049` 场景未开放模板编辑、`40050` 模板内容不合法、`40051` 本章未分析无法按分析修改、`40052` 未生成可用修改稿、`40053` 未配置图像接口、`40054` 封面生成失败、`40055` 封面不存在、`40056` 封面数量达上限、`40057` 场景与接口类型不匹配、`40058` 封面文件丢失、`40059` 势力生成失败、`40060` 智能助手指令无法识别、`40061` 已完成章节禁止编辑正文与标题、`40062` 版本不属于该章节、`40063` 同章已有流在跑、`40064` 已有连写任务在跑、`40065` 该书已有体检任务在跑、`40066` 一致性问题不存在 |
| `5xxxx` | AI | `50001` AI 调用失败、`50002` 结构化解析失败、`50005` 模型列表获取失败 |
| `9xxxx` | 系统 | `90001` 内部错误、`90002` 触发限流 |

---

## 8. 关键约定

- **数据隔离**：业务表统一带 `user_id`；表名登记到 `storyforge.data-permission.managed-tables` 后，MyBatis-Plus 数据权限插件会自动为 SQL 追加 `user_id = ?`（Service 层再做归属二次校验，越权统一返回「资源不存在」）。
  已登记：`t_ai_config`、`t_scene_binding`、`t_prompt_template`、`t_inspiration_skill`、`t_inspiration_session`、`t_inspiration_message`、`t_novel`、`t_setting_doc`、`t_character`、`t_character_attribute`、`t_character_inventory` 等（见 `application.yml`）。`t_user` / `t_audit_log` / `t_token_blacklist` 为平台公共表，不纳入自动隔离。
- **主题（浅/深）**：页面颜色一律用 `--sf-*` 语义变量（`web/src/styles/theme.css`），禁止硬编码色值；深色由 `html.dark` 类名 + Element Plus `dark/css-vars.css` 驱动，SVG/Canvas 等内联属性需按 `theme.isDark` 切两套配色。
- **提示词模板**：数据库只存用户可编辑的「指令段」，输出格式契约由 `PromptSceneRegistry` 提供并在 `PromptTemplateRenderer.compose()` 时追加，**永远不入库**；未接入模板的场景调用方保留硬编码提示词（`system()` 返回空串表示不覆盖）。
- **正文定稿锁定**：工作台「正文」默认查看态，点「编辑」才进入编辑态（切章 / 保存后回到查看态）。章节状态为 `DONE`
  时正文与标题锁定——`NovelAssetServiceImpl.updateChapter` 只在「请求带了不同的正文或标题」时拒绝（`40061`），
  单纯改状态放行。因此「已完成 → 草稿 / 写作中」是用户唯一的解锁入口，也是 AI 写正文（内部 `updateChapter` 带 content）、
  按分析修改、智能助手改稿被拦的依据；新增写正文的路径不需要各自判断，统一走 `updateChapter` 即可。
- **密钥加密**：AI API Key 以 AES-256-GCM 加密入库（`v1:base64(iv|ct)`，随机 IV），只回显掩码。主密钥来自 `STORYFORGE_SECRET_KEY`，
  **上线后不可更换**，否则已存密文无法解密。
- **账号安全**：口令 BCrypt（cost 10）；停用账号每次请求从数据库校验状态，令牌立即失效；登出与刷新轮换的 `jti` 落 `t_token_blacklist`，按 `expires_at` 每小时清理。
- **超管边界**：超管只是多了两个平台菜单，不能停用 / 删除 / 重置自己的口令，也不能把系统最后一个启用的超管停用或降级。
- **数据库变更**：一律通过 `src/main/resources/db/migration/{postgresql,mysql,sqlite}/V*.sql`（Flyway），禁止手工改表。
  当前每种数据库下只有一份 **`V1__baseline.sql`**——它由原来的 V1~V26 共 26 个增量脚本合并而成（2026-10-23），
  是全库结构的唯一基线。**禁止修改该文件的结构内容**；任何变更请新增 `V2__xxx.sql`、`V3__xxx.sql`……
  历史上的 V1..V26 脚本已归档到 `app/data/backup/migrations-v1-v26/`，仅供追溯，不再参与执行。
  已有库（曾经跑过 V1..V26）通过 `spring.flyway.baseline-version=1` 接管：删掉 `flyway_schema_history` 后重启，
  Flyway 会写入一条 BASELINE 记录并跳过 V1 的执行，不会重复建表；全新库则正常执行基线。
  **MySQL / SQLite 两份基线由 `node script/db/gen-baseline.mjs` 从 PostgreSQL 版派生，禁止手工编辑**；
  改结构只改 PostgreSQL 版，再重跑脚本，`--check` 模式可用于校验三份是否同步。
- **SQL 方言纪律**：Mapper XML 与 Java 里不写任何数据库专有语法，需要分支时用 MyBatis 的 `_databaseId`
  （`postgresql` / `mysql` / `sqlite`）。具体取舍：
  - 关键词匹配写 `LOWER(col) LIKE LOWER(CONCAT('%', #{kw}, '%'))`，不用 `ILIKE`；
  - 时间运算（加一天、取日期部分）放 Java 侧算好再传参，不用 `INTERVAL` / `DATE_ADD`；
    确实必须在 SQL 里取日期部分时，走 `DashboardMapper.xml` 的 `dateOf` 片段；
  - 当前时间一律由应用写入（`AuditMetaObjectHandler` 或调用方传参），不依赖数据库时钟——
    SQLite 的 `CURRENT_TIMESTAMP` 是 UTC，与「全库存本地时间」的约定冲突。
- **AI 配置不变量**：同一用户内配置名唯一；每用户至多一个默认端点；默认端点必须启用（停用会自动取消默认标记）；被场景绑定的端点不可删除，需先解绑。
- **MyBatis 占位符限制**：`#{}` 只解析**属性 getter**，不能写方法调用（如 `#{q.keywordLike()}`），否则运行期抛
  `ReflectionException: There is no getter for property named 'xxx()'`。`<if test="...">` 走 OGNL，方法调用没问题。

### 已修复问题（2026-10-16 端到端测试发现）

| 问题 | 现象 | 修复 |
|---|---|---|
| 关键字查询必然 500 | `GET /api/admin/users?keyword=xx` → `90001`，日志 `ReflectionException: no getter for 'keywordLike()'` | `UserMapper.xml` 改为 `<if test="q.keyword != null and q.keyword != ''">` + 包含匹配（2026-10-23 多库改造后统一为 `LOWER(col) LIKE LOWER(CONCAT('%', #{kw}, '%'))`，以兼容三种数据库），并删除 `UserQuery.keywordLike()` |
| 非法枚举值报系统错误 | `POST /api/admin/users` 传 `"role":"BOSS"` → `90001`，误导排查方向 | `GlobalExceptionHandler` 新增 `HttpMessageNotReadableException` 处理 → `10001`，并回显可选值（如「role 取值非法，可选值为 SUPER_ADMIN / USER」） |

### 已修复问题（2026-10-21 三期功能联调发现）

| 问题 | 现象 | 修复 |
|---|---|---|
| 提示词页编译失败 | `npm run build` 报 `Error parsing JavaScript expression: Unexpected token` | 模板里字面量 `{{var}}` 被 Vue 当成插值解析；改为 `varToken()` 拼接生成占位符 |
| 页签样式互相污染 | 角色弹窗的「档案/属性/背包」页签与人物菜单的「人物档案/关系视图」共用 `character-tabs` 类，`margin-top` 被后者覆盖 | 弹窗页签改名 `character-asset-tabs` |
| 属性数值改不动 | 同一次请求内先 wrapper 更新再 `updateById`，第二次因乐观锁版本已变而失败 | `updateInventory` 攒成一个 `LambdaUpdateWrapper` 一次 UPDATE（含 `clearQuantity`） |

---

## 9. 开源协议

本项目以 **GNU Affero General Public License v3.0**（AGPL-3.0，或其更新版本）发布，协议全文见仓库根目录 [LICENSE](./LICENSE)。

Copyright (C) 2026 Jiaqiong

**为什么选 AGPL 而不是 MIT / Apache-2.0**：StoryForge 是一个可直接部署成公开网站的完整应用（前端、后端、数据库迁移、Docker 镜像、部署脚本都在同一个仓库里）。MIT / Apache-2.0 允许他人把代码改成闭源版本、部署成收费站点而不回馈任何修改；AGPL-3.0 的 §13「远程网络交互」条款补上了这个缺口——修改后的版本只要对外提供网络服务，就必须让使用者能够获取对应源码。自己部署、自己用、拿来学习，都不受任何额外约束。

### 9.1 使用边界

| 使用方式 | 是否允许 | 需要做什么 |
|---|---|---|
| 自行部署 / 内部使用 | 允许 | 保留版权与许可声明，不强制公开修改 |
| 修改后自用 | 允许 | 同上 |
| 分发源码、jar、桌面安装包、Docker 镜像 | 允许 | 随附完整对应源码与 LICENSE；修改过的文件需显著标注改动 |
| 修改后部署为对外服务（哪怕只跑在自己服务器上） | 允许 | 依 §13 向所有使用者提供源码获取方式，且该源码同样以 AGPL-3.0 授权 |
| 改造后闭源商用 / 提供闭源 SaaS | 不允许 | 与协议冲突，需另行取得作者书面授权 |

其余要点：AGPL-3.0 含显式专利授权（§11）；不得在被许可作品上附加额外限制（§10）；不提供任何担保、作者不承担使用后果（§15–17）；分发时不得删除版权声明或更改协议名称。

> 协议不可撤销：已经按 AGPL-3.0 发布的版本无法回溯收回，后续若要改为其他协议，只能对新的版本生效。

### 9.2 部署者要做的合规动作（本项目已内置）

AGPL-3.0 不是放一个 LICENSE 文件就够——对外提供网络服务的实例必须把「源码在哪」告诉使用者。本项目已经把入口做在代码里：

| 位置 | 作用 |
|---|---|
| 登录页底部 | 「AGPL-3.0 协议开源 · 获取源码」链接 |
| 顶栏用户菜单 | 「获取源码」「开源许可（AGPL-3.0）」两项 |
| `web/src/config/about.ts` | `SOURCE_REPO_URL` / `LICENSE_URL` 常量，**二次开发者只改这里** |
| `docker/Dockerfile`、`script/deploy/remote/Dockerfile` | OCI 标签 `org.opencontainers.image.licenses=AGPL-3.0-or-later` 与 `...image.source` 指向源码仓库 |
| `pom.xml`、`web/package.json` | 声明 `AGPL-3.0`，供依赖分析工具识别 |

本项目公开实例（[www.jq888.cn](https://www.jq888.cn)）的源码即本仓库，任何人可自由获取。若你把本项目改造成自己的站点对外服务，请把 `about.ts` 里的仓库地址换成你自己的公开仓库，否则不满足 §13。

### 9.3 第三方组件

`pom.xml` 与 `web/package.json` 中的第三方依赖各自沿用原协议（Spring Boot、MyBatis-Plus 等为 Apache-2.0；Vue、Element Plus、Vite 等为 MIT），本项目不对它们重新授权，也不改变它们原有的授权条件。整体分发这些组件时，需继续满足其各自的声明要求（保留版权与许可文本）。

> 以上为便于理解的要点摘录，不构成法律意见；发生歧义时以 [LICENSE](./LICENSE) 英文原文为准。
