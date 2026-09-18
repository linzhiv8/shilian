# 后端镜像：Maven 构建 → JRE 运行。
#
# 关于基础镜像：换到 MySQL 之前这里用的是 sqlite-jdbc，它带**按 glibc 编译**的
# 原生 .so，alpine（musl）加载不了，所以必须用 Debian 系。
# 现在用 mysql-connector-j，那是**纯 Java** 的，这个限制已经不存在了——
# alpine 也能跑。
#
# 但还是留在 Debian 系，两个理由：
#   1. 前端镜像必须用 Debian 系（lightningcss / rolldown 是原生二进制），
#      两个镜像同系，排查问题时少一个变量
#   2. 换基础镜像要重新验一遍，而收益只是镜像小几十 MB
# 真要瘦身的话，可以换成 `eclipse-temurin:21-jre-alpine`——记得实测一次。

# ────────── 构建阶段 ──────────
FROM maven:3.9-eclipse-temurin-21 AS build

WORKDIR /build

# 先只拷 pom 并拉依赖。这样只要依赖没变，改业务代码不会重新下载整个世界。
# 国内网络下这一步最慢，必要时给 Maven 配镜像（见 DEPLOY.md）。
COPY pom.xml .
RUN mvn -B -q dependency:go-offline

COPY src ./src

# 提示词是**构建输入**：pom 里有一段 <resource> 把 tools/prompt-lab/ 的
# 两个 .md 拷进 jar 的 prompts/，应用从 classpath 读它们。
# ⚠ 这一行不能少，而且**少了不会报错** —— Maven 对不存在的资源目录只打一行
#   INFO "skip non existing resourceDirectory" 然后 BUILD SUCCESS，
#   但 jar 里没有 prompts/，容器启动时才抛「找不到提示词文件 prompts/xxx」。
COPY tools/prompt-lab ./tools/prompt-lab

RUN mvn -B -q package

# ────────── 运行阶段 ──────────
FROM eclipse-temurin:21-jre-jammy

# curl 是给下面的 HEALTHCHECK 用的。JRE 镜像里没有，得自己装。
# tzdata 是给 TZ 环境变量用的——没有它，TZ=Asia/Shanghai 解析不出来，
# JVM 会悄悄退回 UTC，时区又错了。装完立刻清 apt 缓存。
RUN apt-get update \
    && apt-get install -y --no-install-recommends curl tzdata \
    && rm -rf /var/lib/apt/lists/*

WORKDIR /app

# 通配符是故意的：版本号变了也不用改这里。
# （Spring Boot 还会生成一个 .jar.original，但它不匹配 *.jar）
COPY --from=build /build/target/shilian-server-*.jar /app/app.jar

# 这里原来有 `RUN mkdir -p /app/data`（SQLite 的落点）。
# 换 MySQL 之后不再需要：数据在数据库里，容器是彻底无状态的。
# 所以这个镜像可以随便删、随便重建，不会丢任何东西。

# JAVA_OPTS 里的 -Duser.timezone 由 compose 拼进去（跟着 TZ 走），
# 因为 Windows 上的 JVM 根本不认 TZ 环境变量，只认系统时区——
# 靠 TZ 一条路走不通，得让 Java 自己知道时区。
ENV JAVA_OPTS="-XX:MaxRAMPercentage=75 -Dfile.encoding=UTF-8"

EXPOSE 8080

# 用 /api/health（SecurityConfig 里是 permitAll，不需要登录）。
# start-period 给足，因为启动要跑迁移 + 加载提示词。
HEALTHCHECK --interval=30s --timeout=5s --start-period=60s --retries=3 \
    CMD curl -fsS http://127.0.0.1:8080/api/health || exit 1

# 用 sh -c 是为了让 $JAVA_OPTS 真的被展开
# （exec 形式不会过 shell，环境变量里写 JVM 参数会原样当参数传给 java）
ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar /app/app.jar"]
