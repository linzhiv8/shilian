# 前端镜像：Node 构建出静态文件 → 塞进 nginx。
#
# 为什么不用 `npm run dev`：那是开发服务器，不压缩、不做缓存头、
# 还带着 HMR 的 websocket。生产要的是 build 出来的静态文件。
#
# 为什么基础镜像是 slim 而不是 alpine：
# 这个项目用的是 tailwind v4 和 vite 8，底层分别依赖 lightningcss 和 rolldown，
# 都是带**原生二进制**的。alpine 是 musl，glibc 编译的 .so 加载不了，
# 会报一个和 JS 完全无关的错。slim 是 Debian，省心。

# ────────── 构建阶段 ──────────
FROM node:22-slim AS build

WORKDIR /app

# 先只拷依赖清单。这样只要依赖没变，这一层就命中缓存，
# 改业务代码不会重新装一遍 node_modules。
COPY package.json package-lock.json ./
RUN npm ci

COPY . .
RUN npm run build

# ────────── 运行阶段 ──────────
FROM nginx:1.27-alpine

# 我们的 nginx 配置：静态文件 + 把 /api 反代到后端容器
COPY nginx.conf /etc/nginx/conf.d/default.conf

COPY --from=build /app/dist /usr/share/nginx/html

EXPOSE 80
