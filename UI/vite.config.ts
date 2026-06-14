import { defineConfig } from "vite";
import react from "@vitejs/plugin-react";

export default defineConfig({
  base: "/_/",
  plugins: [react()],
  build: {
    outDir: "../src/main/resources/pocketbase-admin",
    emptyOutDir: true,
    assetsDir: "assets"
  },
  server: {
    port: 5173,
    strictPort: false,
    proxy: {
      "/api": {
        target: "http://127.0.0.1:8090",
        changeOrigin: true
      }
    }
  }
});
