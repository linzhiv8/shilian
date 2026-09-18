# 部署到 Linux

**本地编译，服务器只负责跑。** 服务器上不装 Maven、不装 Node、不 build 任何东西 ——
传上去的就是能直接运行的产物。

```
浏览器
  │ :80
  ▼
nginx（Docker 容器，--network host）
  ├── /          → 静态文件（本地 npm run build 出来的 dist）
  └── /api/      → 反代到 127.0.0.1:8080
                        │
                        ▼
                  java -jar（systemd 常驻，只监听 127.0.0.1:8080）
                        │ JDBC
                        ▼
                  MySQL（服务器上现成的那个）
```

nginx 用 `--network host` 是为了让「容器里的 127.0.0.1」等于「宿主机的 127.0.0.1」，
这样后端可以继续只监听 loopback —— **8080 完全不用对公网开放**。

## 服务器上要装什么

| | 版本 | 说明 |
|---|---|---|
| JDK | **21**（Temurin） | 和本地同一个大版本即可，见第 1 步 |
| Docker | 任意近期版本 | **只用来跑 nginx 这一个容器** |
| MySQL | 5.7 / 8.0+ | 库和账号要建一次 |

**不需要装**：Maven、Node、宿主机上的 nginx。

## 第 1 步 · 服务器装 JDK 21

本地用的是 **Temurin 21.0.12.1**。服务器装同一个大版本就行 —— **补丁号不必一致**，
class 文件的版本号只由大版本决定，21.0.12 编出来的 jar 在任何 21.x 上都能跑。

```bash
# 清华 TUNA 镜像。这个文件名就是 21.0.12.1+1，和本地同一个构建
curl -L -o /tmp/jdk21.tar.gz \
  "https://mirrors.tuna.tsinghua.edu.cn/Adoptium/21/jdk/x64/linux/OpenJDK21U-jdk_x64_linux_hotspot_21.0.12.1_1.tar.gz"

ls -l /tmp/jdk21.tar.gz           # 应该是 207473347 字节（约 198 MB）

mkdir -p /opt/jdk21
tar -xzf /tmp/jdk21.tar.gz -C /opt/jdk21 --strip-components=1
/opt/jdk21/bin/java -version      # 应打印 openjdk version "21.0.12.1"
```

⚠ **不要用 Adoptium 的官方接口**（`api.adoptium.net/v3/binary/latest/...`）：
它会 307 重定向到 `github.com/adoptium/...`，国内拉 GitHub Release 会卡在
**0 字节不动**直到超时（不是慢，是连不上）。清华镜像里就有同一个文件。

**兜底**（清华源也慢的话）：用系统自带的包管理器装发行版 JDK 21。
先 `cat /etc/os-release` 看是什么系统：

```bash
yum install -y java-21-openjdk       # Alibaba Cloud Linux / CentOS / Rocky
apt install -y openjdk-21-jdk        # Ubuntu 24.04（22.04 自带的是 17，没有 21）
```

装完用 `java -version` 确认是 21。**用这种方式装的 JDK 在
`/usr/bin/java`，下面 systemd 里的 `ExecStart` 路径要跟着改。**

装到 `/opt/jdk21` 的好处是路径固定、不用配 `update-alternatives`，
也不受发行版有没有这个包的影响。

## 第 2 步 · 本地打包

两个产物，都在本机生成。

**后端**（Git Bash 里，必须用 `./mvn.sh`，直接敲 `mvn` 会报 classworlds 错）：

```bash
cd server
./mvn.sh clean package
```

产物：`server/target/shilian-server-0.1.0.jar`

提示词文件在打包时已经被 pom 拷进 jar 的 `prompts/` 了，**所以只传这一个 jar 就够**。

**前端**：

```bash
cd web
npm run build
```

产物：`web/dist/`。（`npm run build` 是 `tsc -b && vite build`，有类型错误会直接失败。）

## 第 3 步 · 传到服务器

```bash
cd "D:/workspace/shilian"

scp server/target/shilian-server-0.1.0.jar  root@<服务器IP>:/opt/shilian/app.jar
scp -r web/dist                             root@<服务器IP>:/opt/shilian/dist
scp web/nginx.conf                          root@<服务器IP>:/opt/shilian/nginx.conf
```

`/opt/shilian` 不存在的话先建：`ssh root@<服务器IP> "mkdir -p /opt/shilian"`。

改名成 `app.jar` 是为了以后升级不用动 systemd 里的路径。

**`scp -r` 直接传目录就行，不用先压成 zip** —— 服务器上不一定有 `unzip`
（Alibaba Cloud Linux 默认就没装，`unzip: command not found`）。
网络不稳、非要打包传的话用 **tar**，别用 zip：

```bash
# 本机
tar -czf dist.tar.gz -C web dist
scp dist.tar.gz root@<服务器IP>:/opt/shilian/

# 服务器上（tar 一定有）
tar -xzf /opt/shilian/dist.tar.gz -C /opt/shilian/
```

**传完检查一下**：`ls /opt/shilian/dist/index.html` 必须存在。
挂载给 nginx 的是**包含 `index.html` 的那一层**，多套一层 `dist/dist/` 就会 403。

`/opt/shilian` 最终应该长这样：

```
/opt/shilian/
├── app.jar            后端（第 2 步打的包）
├── dist/              前端产物，index.html 直接在里面
├── nginx.conf         第 7 步挂给 nginx 容器
└── .env.properties    第 5 步写的配置
```

## 第 4 步 · 建库和账号（只做一次）

**先确认 MySQL 长在哪**，这决定了下面两条命令怎么写：

```bash
systemctl is-active mysqld mysql 2>/dev/null                    # 宿主机上有没有
docker ps --format '{{.Names}}\t{{.Ports}}' | grep -i mysql     # 有没有跑在容器里
```

### 情况 A：MySQL 跑在 Docker 里

容器即使映射了 `-p 3306:3306`，**从宿主机连进去时 MySQL 看到的源 IP 是 docker0 的网关
（172.17.0.1），不是 localhost** —— 所以账号的 host 必须写 `'%'`。

写成 `'localhost'` 会认证失败，报 `Access denied`，**看起来像密码错了**，
于是很容易去改密码，改完还是不行。

```bash
# 忘了 root 密码就从容器里读出来（当初 docker run 时设的那个）
docker inspect mysql8 --format '{{range .Config.Env}}{{println .}}{{end}}' | grep -i mysql

docker exec -i mysql8 mysql -uroot -p'root密码' <<'SQL'
CREATE DATABASE IF NOT EXISTS shilian CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
CREATE USER IF NOT EXISTS 'shilian'@'%' IDENTIFIED BY '换成你的密码';
GRANT ALL PRIVILEGES ON shilian.* TO 'shilian'@'%';
FLUSH PRIVILEGES;
SQL
```

配置里的 `SPRING_DATASOURCE_URL` **仍然写 `127.0.0.1:3306`** —— 宿主机上的
`127.0.0.1:3306` 会被端口映射转发进容器，这条是通的。

### 情况 B：MySQL 直接装在宿主机上

```bash
mysql -u root -p <<'SQL'
CREATE DATABASE IF NOT EXISTS shilian
  CHARACTER SET utf8mb4
  COLLATE utf8mb4_unicode_ci;
CREATE USER IF NOT EXISTS 'shilian'@'localhost' IDENTIFIED BY '换成你的密码';
GRANT ALL PRIVILEGES ON shilian.* TO 'shilian'@'localhost';
FLUSH PRIVILEGES;
SQL
```

这种情况用 `'localhost'` 更严 —— 后端就在本机，没必要开远程登录。

### 两种情况的共同点

两个 `IF NOT EXISTS` 是故意加的：**库或账号已经存在时不报错**，可以放心重跑，
已有的数据一条都不会动。

⚠ 只建**库**和**账号**，**不要建表** —— 表由应用启动时自己建（见第 7 步）。

`CHARACTER SET utf8mb4` 不能省，理由见「必须知道的点」第 3 条。

## 第 5 步 · 写配置

```bash
cat > /opt/shilian/.env.properties <<'EOF'
SPRING_DATASOURCE_URL=jdbc:mysql://127.0.0.1:3306/shilian?useSSL=false&allowPublicKeyRetrieval=true&connectionTimeZone=LOCAL&rewriteBatchedStatements=true
SPRING_DATASOURCE_USERNAME=shilian
SPRING_DATASOURCE_PASSWORD=第4步设的密码
DEEPSEEK_API_KEY=sk-你的key
logging.level.com.shilian=INFO
SERVER_FORWARD_HEADERS_STRATEGY=native
server.servlet.session.cookie.same-site=lax
server.servlet.session.cookie.secure=false
EOF

chmod 600 /opt/shilian/.env.properties    # 里面有数据库密码和 API Key
```

这个文件里两种键名风格是**故意混用**的，不是笔误：

- `SPRING_DATASOURCE_*` / `DEEPSEEK_API_KEY` 用大写+下划线，因为 `application.yml`
  里有同名的 `${}` 占位符（`${SPRING_DATASOURCE_URL:...}` 这种）；
- 其余用点号键（`server.servlet.session.cookie.same-site`），因为 yml 里没有占位符。

**大写+下划线的键只在 yml 有同名占位符时才会被读到**——它不会自动映射成
`server.xxx.yyy`。Spring Boot 的 relaxed binding 只对真实环境变量做「大写 → 点号」
的转换；`.properties` 文件走的是逐字比较，`SERVER_SERVLET_SESSION_COOKIE_SECURE`
永远匹配不上 `server.servlet.session.cookie.secure`。写错形式**不报错、也不生效**，
排查起来非常费时间——所以这里宁可写成难看的点号键，也别写成看着规整的大写键。

⚠ `SERVER_FORWARD_HEADERS_STRATEGY` 必须是 `native`，**不是 `framework`**。

nginx 那边用的是 `proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;`
（追加语义），客户端自己送来的 `X-Forwarded-For` 会原样留在前面、nginx 只把真实
地址追加在最后，于是这个头是「`<伪造值>, <真实 IP>`」。`framework`（Spring 的
`ForwardedHeaderFilter`）取**最左**值 = 拿到伪造值，任何人加一个
`X-Forwarded-For: 1.2.3.4` 就能把登录/注册限流整个绕过去；`native`（Tomcat 的
`RemoteIpValve`）**从右往左跳过可信代理**，拿到的是 nginx 追加的真实地址，且只在
直连方是私有/回环地址时才采信（nginx 从 `127.0.0.1` 连过来正好命中）。

`DEEPSEEK_API_KEY` 直接从本地 `server/.env.properties` 里复制那一行。

抓取代理那两项（`SHILIAN_FETCH_PROXY_HOST` / `_PORT`）**先不写**：
服务器能直连外网就不需要；等抓取报 `HttpConnectTimeoutException` 再加，
值写 `127.0.0.1`（后端就在本机，不是容器里）。

这个文件和本地那个 `server/.env.properties` **是一回事**，键名规则也一样：
必须和 `application.yml` 里的占位符同名（`SPRING_DATASOURCE_URL`，大写+下划线）。
写成 `spring.datasource.url` **不生效而且不报错**。

应用读的是**工作目录**下的 `.env.properties`（`application.yml` 里那句
`spring.config.import: optional:file:./.env.properties`），所以下面 systemd 的
`WorkingDirectory` 必须是 `/opt/shilian` —— 否则这个文件读不到，Key 全是空，**而且不报错**。

**判据**：配置没生效时日志里 `com.shilian` 还在打 DEBUG（yml 的默认值就是 DEBUG），
生效了才是 INFO。用这个反推配置有没有读到，比猜快。

这个文件和本地那个 `server/.env.properties` **是一回事**，键名规则也一样：
必须和 `application.yml` 里的占位符同名（`SPRING_DATASOURCE_URL`，大写+下划线）。
写成 `spring.datasource.url` **不生效而且不报错**。

应用读的是**工作目录**下的 `.env.properties`（`application.yml` 里那句
`spring.config.import: optional:file:./.env.properties`），所以下面 systemd 的
`WorkingDirectory` 必须是 `/opt/shilian` —— 否则这个文件读不到，Key 全是空。

## 第 6 步 · 起后端（systemd）

### 先前台试跑一次

```bash
cd /opt/shilian
/opt/jdk21/bin/java -jar app.jar
```

**先这么跑一遍是有意义的**：日志直接打在屏幕上，配置有问题当场就能看见。
看到「数据库连接正常」那几行就说明对了，`Ctrl+C` 停掉，再交给 systemd 常驻。

（连不上库时应用给的是可读提示而不是一坨堆栈，这是
`DatabaseUnreachableFailureAnalyzer` 干的。但它只在**启动阶段**连不上时生效。）

### 再交给 systemd 常驻

直接敲命令写文件，比手动 `vi` 省事：

```bash
cat > /etc/systemd/system/shilian.service <<'EOF'
[Unit]
Description=Shilian API
After=network.target

[Service]
Type=simple
WorkingDirectory=/opt/shilian
Environment=TZ=Asia/Shanghai
ExecStart=/opt/jdk21/bin/java -XX:MaxRAMPercentage=75 -Dfile.encoding=UTF-8 -Duser.timezone=Asia/Shanghai -jar /opt/shilian/app.jar
Restart=always
RestartSec=5

[Install]
WantedBy=multi-user.target
EOF

systemctl daemon-reload
systemctl enable --now shilian
systemctl status shilian --no-pager
```

`ExecStart` 特意写成**一整行**：systemd 是支持行尾反斜杠续行的，但后面多一个空格
就会解析错，一行反而更稳。

`Restart=always` + `RestartSec=5` 就是「不断」的来源 —— 进程崩了 5 秒后自动拉起；
`systemctl enable` 让它开机自启。**SSH 断开不影响它**，进程由 systemd 托管，
不是挂在你的终端上。

看日志：

```bash
journalctl -u shilian -f        # 跟着看，Ctrl+C 退出（只是不看，服务照跑）
journalctl -u shilian -n 50     # 只看最近 50 行
```

改完 `.env.properties` 或换了 jar 之后：

```bash
systemctl restart shilian
```

时区给了两道保险：`Environment=TZ` 管系统层，`-Duser.timezone` 管 Java 层。
只给 TZ 是不够的 —— JVM 在某些平台上不认这个环境变量。

**JDK 是用 `yum` / `apt` 装的话**（不是解压到 `/opt/jdk21`），把 `ExecStart` 里那个
`/opt/jdk21/bin/java` 换成 `/usr/bin/java`，先 `which java` 确认一下路径。

## 第 7 步 · 起 nginx（Docker）

```bash
docker run -d \
  --name shilian-web \
  --restart unless-stopped \
  --network host \
  -v /opt/shilian/nginx.conf:/etc/nginx/conf.d/default.conf:ro \
  -v /opt/shilian/dist:/usr/share/nginx/html:ro \
  nginx:1.27-alpine
```

**`--network host` 是关键**：容器和宿主机共用网络栈，所以 `nginx.conf` 里那句
`proxy_pass http://127.0.0.1:8080` 打到的是宿主机上那个 jar。

如果换成默认的 bridge 网络 + `-p 80:80`，容器里的 `127.0.0.1` 就变成**容器自己**了，
后端必须改成监听 `0.0.0.0`（等于把 8080 暴露出去），配置也得跟着改。

用了 host 网络就**不要再写 `-p 80:80`**，会被忽略并打一条警告。
host 网络下 nginx 直接占用宿主机的 80，所以这台机器上不能再有别的东西占 80。

## 第 8 步 · 验证

```bash
curl -fsS http://127.0.0.1:8080/api/health && echo      # 后端直连
curl -fsS http://127.0.0.1/api/health && echo           # 经过 nginx
```

`/api/health` 在 `SecurityConfig` 里是 `permitAll`，不需要登录。

启动日志（`journalctl -u shilian -n 40 --no-pager`）里应该看到这几行：

```
工作目录 /opt/shilian
数据库   jdbc:mysql://127.0.0.1:3306/shilian?...
数据库连接正常 MySQL 8.0.x
schema 版本 1 · 链接 0 条 · 账号 0 个
AI 配置 已就绪（模型 deepseek-chat）
抓取代理 未配置（直连）
```

**没有 `⚠` 就说明配置是对的。** 表也是这时候建出来的，不用手工跑任何 SQL。

最后浏览器打开 `http://<服务器IP>`，注册第一个账号。

⚠ **注册接口是对外开放的**（`permitAll`），注册完自己之后建议关掉。

## 更新代码

本地重新打包（第 2 步），只传变的那部分，重启对应服务：

```bash
# 后端
scp server/target/shilian-server-0.1.0.jar root@<服务器IP>:/opt/shilian/app.jar
ssh root@<服务器IP> "systemctl restart shilian"

# 前端
scp -r web/dist root@<服务器IP>:/opt/shilian/dist
```

前端**不用重启 nginx** —— `dist` 是挂载目录，文件换了就是新的。
（浏览器可能缓存 `index.html`，配置里已经给它加了 `no-cache`。）

## 几个必须知道的点

### 1. 后端为什么只监听 127.0.0.1

`application.yml` 里写死了 `server.address: 127.0.0.1`。在 host 网络模式下这是最优的：
nginx 容器能通过宿主机的 loopback 访问它，而**外面完全访问不到 8080**，少一个暴露面。

所以**不要**为了「让容器连上」去改成 `0.0.0.0` —— 那是 bridge 网络下才需要的。

### 2. MySQL 的 127.0.0.1 这次是对的

MySQL 是网络服务，「连哪个主机」取决于**从哪儿连**。这次后端是宿主机上的普通进程，
所以 `127.0.0.1` 就是这台服务器本身 —— 写对了。

（之前那套把后端也塞进 Docker 的方案里，这里必须写 `host.docker.internal`，
因为容器里的 127.0.0.1 是容器自己。现在没有那一层了。）

只有 MySQL 跑在**另一台机器**上才要改：把那台的 IP 填进 `SPRING_DATASOURCE_URL`，
并确认那台的 `bind-address` 允许远程连接。

### 3. 库必须是 utf8mb4

MySQL 的 `utf8` 是**假的 UTF-8**（最多 3 字节，存不下 emoji 和部分汉字）。
建库时用默认字符集的话，中文可能存进去变成问号，而且**全程没有任何报错**。
第 4 步的 `CHARACTER SET utf8mb4` 就是为这个。已经建错了的话，改字符集不会修好已有数据。

### 4. 抓取代理：这次 127.0.0.1 也是对的

服务器在国内的话，抓 github.com 这类站会 TCP 超时（`HttpConnectTimeoutException`），
这时在服务器上起个代理，配置里写 `127.0.0.1:7897` 就行 —— 后端就在宿主机上。
境外服务器一般能直连，整段删掉。

为什么 Java 要单独配代理：它**不读系统代理**（Windows 注册表和 `http_proxy` 环境变量都不看）。
所以「浏览器能打开、后端抓不到」= 代理问题，不是网站问题。

### 5. `url_normalized VARCHAR(700)` 别随手加大

唯一索引是 `(user_id, url_normalized)`，InnoDB 索引键上限 3072 字节：
`32×4 + 700×4 = 2928`，余量只剩 144 字节。列宽和 `LinkRepository.MAX_URL_NORMALIZED`
是绑在一起的，改一个必须改另一个，超了报 `Specified key was too long`。

## 备份

数据在 MySQL 里，所以备份是 `mysqldump` 的事，不是「拷文件」的事。

```bash
cat > /etc/cron.d/shilian-backup <<'EOF'
0 3 * * * root mysqldump --single-transaction --routines --triggers \
  -u shilian -p'密码' shilian | gzip > /backup/shilian-$(date +\%F).sql.gz
EOF
```

`--single-transaction` 不能省：InnoDB 下它让 dump 拿到一个**一致的快照**，不用锁表。

恢复：

```bash
gunzip -c /backup/shilian-2026-09-18.sql.gz | mysql -u shilian -p shilian
```

## 上 HTTPS

登录态走的是 session cookie，**明文 HTTP 在网上等于把 cookie 挂在外面**，放公网就一定要上 TLS。

最省事的是加一个 Caddy 容器自动申请证书（需要一个指向这台机器的域名）。
**nginx 不用改网络模式，只改一个数字**：把 `nginx.conf` 里的 `listen 80` 改成 `listen 8081`，
重新传上去并 `docker restart shilian-web`。

```bash
# Caddyfile
cat > /opt/shilian/Caddyfile <<'EOF'
shilian.example.com {
    reverse_proxy 127.0.0.1:8081
}
EOF

docker run -d --name caddy --restart unless-stopped --network host \
  -v /opt/shilian/Caddyfile:/etc/caddy/Caddyfile:ro \
  -v caddy-data:/data -v caddy-config:/config \
  caddy:2-alpine
```

然后 `.env.properties` 里改成：

```properties
SERVER_SERVLET_SESSION_COOKIE_SECURE=true
```

再 `systemctl restart shilian`。**不改这一项的话，浏览器不会在 HTTPS 下发 cookie，登录会一直掉。**

另外**远端的 MySQL 建议开 TLS**：JDBC URL 里现在是 `useSSL=false`
（为了省掉自签证书的麻烦），数据库不在同一台机器上时，这条连接上的密码和数据都是明文。

## 常见问题

**页面报「连不上后端服务」**

按这个顺序查，三步就能定位到是哪一层：

```bash
# 1. 后端到底起来没有
journalctl -u shilian -n 80 --no-pager

# 2. 8080 在听吗
ss -lntp | grep 8080

# 3. 直接打后端，绕过 nginx
curl -fsS http://127.0.0.1:8080/api/health && echo
```

- **第 3 步通了** → 后端没问题，是 nginx 到后端这一段。
  看 `docker ps --format '{{.Names}}\t{{.Ports}}'`：web 那行如果显示
  `0.0.0.0:80->80/tcp`，说明它是 **bridge 网络** —— 容器里的 `127.0.0.1` 是容器自己，
  连不到宿主机的 8080。删掉重建并加上 `--network host`（见第 7 步）。
- **第 3 步不通** → 后端自己没起来，看第 1 步日志的尾部。
- **`ss` 里根本没有 8080** → 进程还在启动，或者启动到一半失败退出了。

⚠ **`systemctl status` 显示 `active (running)` 不等于启动成功。**
Spring Boot 启动失败会退出进程，而 `Restart=always` 会立刻把它拉起来 ——
你看到的是一串「running」，实际是在反复崩溃重启。
**唯一的判据是日志里有没有 `Started ShilianApplication in X seconds`。**

**日志里 `com.shilian` 打的是 DEBUG，说明 `.env.properties` 没被读到**
→ `application.yml` 里 `logging.level.com.shilian` 的默认值是 DEBUG，
第 5 步那份配置里写的是 `LOGGING_LEVEL_COM_SHILIAN=INFO`。
如果日志还在打 DEBUG，就是这个文件没生效，**数据库配置、API Key 大概率也是空的**。

查两点：文件在不在 `ls -l /opt/shilian/.env.properties`；
systemd 里的 `WorkingDirectory` 是不是 `/opt/shilian`（配置是相对工作目录找的）。
改完 `systemctl restart shilian`。

**后端起来了但接口全 502**
→ 同上，先跑第 3 步的 `curl`。502 是 nginx 连不上后端，不是后端返回的错误。

**`Communications link failure` / 数据库连接一直失败**
→ 后端在宿主机上，所以先直接试：`mysql -h 127.0.0.1 -u shilian -p shilian`。
通了说明是配置问题（检查 `.env.properties` 的键名和 `WorkingDirectory`）；
不通说明是 MySQL 本身（服务没起、账号没建、`bind-address` 限制）。

**报 `Public Key Retrieval is not allowed`**
→ MySQL 8 的 `caching_sha2_password` 在没开 TLS 时的限制。JDBC URL 里已经带了
`allowPublicKeyRetrieval=true`，如果还报，说明实际用的不是这份配置。

**中文存进去变成 `???`**
→ 库的字符集不是 `utf8mb4`，见「必须知道的点」第 3 条。

**报 `Specified key was too long`**
→ 见第 5 条，多半是有人加大了 `url_normalized`。

**登录后立刻掉线 / cookie 存不住**
→ 大概率是 `SERVER_SERVLET_SESSION_COOKIE_SECURE=true` 但你在用 HTTP 访问。
改成 `false`，或者把 HTTPS 配上。

**时间不对（周报 / 回顾的天数差一天）**
→ 三处要对齐：systemd 里的 `TZ` 和 `-Duser.timezone`、
JDBC URL 里的 `connectionTimeZone=LOCAL`、以及 MySQL 服务器自己的 `time_zone`。

**前端改了但页面没变**
→ 先强制刷新（`Ctrl+F5`）。还不行就确认 `dist` 传到了 `/opt/shilian/dist`
（是挂载目录，不是拷进容器），`docker exec shilian-web ls /usr/share/nginx/html`。
