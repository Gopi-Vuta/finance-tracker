import {defineConfig} from 'vite';
export default defineConfig({
  server: {
    host:'127.0.0.1',
    proxy: Object.fromEntries(['/api','/oauth2','/login'].map(path=>[path,{target:'http://localhost:8080',changeOrigin:false}]))
  }
});
