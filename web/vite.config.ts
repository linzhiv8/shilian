import react from "@vitejs/plugin-react";
import tailwindcss from "@tailwindcss/vite";
import { defineConfig } from "vite";

export default defineConfig({
  plugins: [react(), tailwindcss()],
  server: {
    host: "127.0.0.1",
    port: 5178,
    // 把 /api 代理到后端，前端一律用相对路径。
    // 比让前端硬编码后端地址好：换端口只改这一处，而且生产构建
    // 如果和前端同源部署也不用动代码。
    // （后端那边其实也配了 CORS，那是直连时的兜底，走代理就用不上。）
    proxy: {
      "/api": {
        target: "http://127.0.0.1:8080",
        changeOrigin: false,
      },
    },
  },
});
