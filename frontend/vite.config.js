import { defineConfig } from 'vite'
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
  }
})