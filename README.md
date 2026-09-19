# 拾链 · Linkweft

> 丢一个网址进来，AI 补完标题、摘要、备注、分类、标签。你只做点选，不做填空。

个人网址收藏库。和普通书签的区别在「存」之后——AI 会把网页读一遍，
把一条光秃秃的链接变成一个能检索、能回顾、能想起来的条目。

## 它解决什么问题

收藏夹的通病是「存了不看」。存的时候只有一句"以后再看"，回头看的时候
既想不起来当初为什么存，也不知道这网站是干嘛的。拾链把这两头都接了过去：

- **录入时**：抓正文 → AI 分析 → 生成标题、摘要、备注候选、领域/用途/标签
- **之后**：用「回顾」和「周报」把旧条目重新推回你面前，而不是让它们烂在列表里

设计上的取舍只有一条：**凡是需要用户打字的环节，都要重新设计成点选。**

## 功能

- **一句话入库**：粘贴 URL，自动抓取正文并分析，生成完整元信息
- **备注三选一**：AI 给三句候选，点一下就行；也支持自己写，自己写的那句会被保留
- **书签小工具**：浏览器书签栏点一下，跳到拾链并自动分析当前页
- **补正文**：SPA、需登录、被 Cloudflare 挡住的站点抓不到正文时，可以贴一段正文重新分析
- **筛选与搜索**：领域 / 用途多维筛选，关键词即时过滤，支持多种排序
- **语义搜索**：按意思找，不用记当初存的是什么标题（需额外配一个向量服务）
- **标签管理**：重命名、合并、删除
- **回顾**：一次只给一张卡片，四个动作——打开看看 / 留下 / 看过了 / 删掉
- **周报**：AI 写的一段总结 + 统计数字
- **导出**：把收藏导出成文件
- **账号**：注册登录、改密码、邮箱验证与忘记密码（需要配 SMTP）
- **管理端**：用户管理与审计日志
- 深浅色主题

## 技术栈

| 层 | 用了什么 |
|---|---|
| 前端 | Vite 8 + React 19 + TypeScript + Tailwind CSS 4 |
| 后端 | Spring Boot 3.5 + Java 21 |
| 数据库 | MySQL 8（MyBatis-Plus） |
| AI | DeepSeek 对话接口；语义搜索另接一个 embedding 服务（OpenAI `/embeddings` 协议） |

前端和后端是两个平级目录：`web/` 和 `server/`。

## 快速开始

需要 JDK 21、Maven、Node.js，以及**一个能连上的 MySQL**。

### 后端

```bash
cd server
cp .env.properties.example .env.properties   # 填 DEEPSEEK_API_KEY 和 MySQL 连接
./mvn.sh spring-boot:run                     # 起在 127.0.0.1:8080
```

> 用 Git Bash 时请用 `./mvn.sh`，直接 `mvn` 在部分环境会报 classworlds 错。

### 前端

```bash
cd web
npm install
npm run dev                                  # 起在 127.0.0.1:5178
```

打开 http://127.0.0.1:5178/ 。前端通过 Vite 代理把 `/api` 转发到 8080，
后端没起的时候页面会直接提示「后端没连上」并给出启动命令，不会静默失败。

### 建表

应用启动时会自动建表（读 `server/src/main/resources/db/V1__baseline.sql`）。
想手工执行就用 `sql/schema.sql`，7 张表全是 `CREATE TABLE IF NOT EXISTS`，可以重复跑。

## 配置

配置写在 `server/.env.properties`（已 gitignore，模板见 `server/.env.properties.example`）。

| 变量 | 说明 |
|---|---|
| `SPRING_DATASOURCE_URL` / `_USERNAME` / `_PASSWORD` | MySQL 连接。**必须用这种大写 + 下划线的名字**，与 `application.yml` 里的占位符同名才接得上；不填则用默认值（本机 3306 的 `shilian` 库） |
| `DEEPSEEK_API_KEY` | AI 分析用的 Key。可选 `DEEPSEEK_BASE_URL` / `DEEPSEEK_MODEL` |
| `EMBEDDING_BASE_URL` / `_MODEL` / `_API_KEY` | 语义搜索用的向量服务，**可选**。不配那个按钮就是灰的，其余功能不受影响 |
| `shilian.fetch.proxy.host` / `.port` | 抓取走的 HTTP 代理，可选。国内直连 github.com 这类站点会在 TCP 阶段超时，而 Java 不读系统代理，所以需要显式指定 |
| `SPRING_MAIL_*` | 发验证/重置邮件用的 SMTP，可选 |

DeepSeek 只提供对话接口、**没有 embeddings 接口**，所以向量得来自另一套服务。
本地 Ollama 和硅基流动都能用，具体填法见 `.env.properties.example` 的注释。

## 目录结构

```
web/                     前端（Vite + React 19）
  src/
    App.tsx              布局、状态、筛选逻辑、视图切换
    api.ts               后端接口封装 + 错误归一化
    components/          顶栏、条目、收藏抽屉、回顾、周报、标签管理、管理端…
    index.css            设计令牌（颜色 / 深浅色 / 动画）
  public/
    bookmarklet.html     书签小工具的安装页

server/                  后端（Spring Boot 3 + MySQL）
  src/main/java/com/shilian/
    analyze/             抓取 → 抽取 → 提示词 → 调用 → 校验 → 重试
    search/              语义搜索：向量客户端 + 余弦排序
    web/                 REST 接口
  src/main/resources/db/V1__baseline.sql   建表语句，同时也是迁移列表的 V1
  tools/prompt-lab/      提示词源文件 ★ 会被打包进 jar，别删

sql/schema.sql           MySQL 建表语句（建库 / 部署时手工执行用）
```

## 接口

主要接口都在 `/api` 下：

| 路径 | 作用 |
|---|---|
| `POST /api/analyze` | 分析一个网址，返回草稿 |
| `POST /api/links` · `/api/links/quick` | 保存收藏 / 快速保存 |
| `GET /api/links` | 列表查询（`domain` / `purposes` / `q` / `sort` 等参数） |
| `PATCH /api/links/{id}` | 编辑标题、摘要、备注、分类、标签 |
| `POST /api/links/{id}/reanalyze` · `/apply` | 补正文重分析 / 应用草稿 |
| `GET /api/review` · `/weekly` · `/count` | 回顾队列 / 周报 / 待回顾数量 |
| `GET /api/search/semantic` | 语义搜索 |
| `GET /api/tags` · `POST /api/tags/{rename,merge,delete}` | 标签管理 |
| `GET /api/export` | 导出收藏 |
| `POST /api/auth/{register,login,password,forgot,reset}` | 账号相关 |
| `GET /api/admin/users` · `/audit` | 管理端 |
| `GET /api/meta` · `/health` | 枚举字典 / 健康检查 |

## 文档

- [`server/README.md`](server/README.md) —— 后端结构、启动与排错
- [`server/ARCHITECTURE.md`](server/ARCHITECTURE.md) —— 分层与依赖方向

## 说明

个人项目，单人开发，只保证在自己这套环境上跑通（Windows 开发、Linux 部署）。
没有自动化测试，改动后靠手工验证。

## License

[MIT](LICENSE)
