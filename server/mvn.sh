#!/usr/bin/env bash
# Maven 包装脚本。
#
# 为什么要它：Git Bash 下直接跑 `mvn` 会报
#   ClassNotFoundException: org.codehaus.plexus.classworlds.launcher.Launcher
# 因为 Maven 自带的 mvn 是 POSIX sh 脚本，在 Git Bash 里算不出自己的安装路径，
# 也就拼不出 -classpath 和 -Dclassworlds.conf。
# 这里把四个必需的 system property 显式写死，绕过脚本本身。
#
# 用法（在 server/ 目录下）：
#   ./mvn.sh -v               看版本
#   ./mvn.sh clean package    构建
#   ./mvn.sh spring-boot:run  起服务

set -euo pipefail

MAVEN_HOME="${MAVEN_HOME:-D:/app/maven}"

if [ ! -f "$MAVEN_HOME/boot/plexus-classworlds-2.9.0.jar" ]; then
  echo "找不到 Maven：$MAVEN_HOME" >&2
  echo "请设置 MAVEN_HOME 环境变量指向 Maven 安装目录" >&2
  exit 1
fi

exec java \
  -classpath "$MAVEN_HOME/boot/plexus-classworlds-2.9.0.jar" \
  "-Dclassworlds.conf=$MAVEN_HOME/bin/m2.conf" \
  "-Dmaven.home=$MAVEN_HOME" \
  "-Dmaven.multiModuleProjectDirectory=$(pwd)" \
  org.codehaus.plexus.classworlds.launcher.Launcher "$@"
