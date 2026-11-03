import { defineConfig } from 'vitest/config'
import react from '@vitejs/plugin-react'
import basicSsl from '@vitejs/plugin-basic-ssl'

// https://vite.dev/config/
export default defineConfig({
  plugins: [
    react(),
    basicSsl() // Tự động tạo chứng chỉ SSL cho môi trường local
  ],
  server: {
    https: true, // Bật HTTPS
    port: 5173   // Cố định cổng chạy frontend
  },
  test: {
    environment: 'jsdom',
    setupFiles: './src/test/setup.js',
    coverage: {
      provider: 'v8',
      reporter: ['text', 'lcov', 'json-summary'],
      reportsDirectory: './coverage',
      // Baseline ban đầu được đo từ các luồng quan trọng (auth, API retry, giỏ hàng, admin).
      // Khi suite lớn hơn, tăng dần thay vì ép một con số cao và khuyến khích test hình thức.
      thresholds: {
        statements: 50,
        branches: 35,
        functions: 40,
        lines: 50,
      },
    }
  }
})
