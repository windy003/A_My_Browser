# Cloudflare Worker 部署步骤

## 前置条件
- 安装 Node.js
- 有 Cloudflare 账户

## 步骤

### 1. 安装依赖
```bash
cd cloudflare-worker
npm install
```

### 2. 登录 Cloudflare
```bash
npx wrangler login
```

### 3. 创建 D1 数据库
```bash
npx wrangler d1 create bookmark-sync-db
```
执行后会输出 database_id，把它复制到 `wrangler.toml` 中替换 `placeholder-replace-after-create`。

### 4. 初始化数据库表
```bash
npx wrangler d1 execute bookmark-sync-db --remote --file=./schema.sql
```

### 5. 设置 JWT 密钥
```bash
npx wrangler secret put JWT_SECRET
```
输入一个随机字符串作为密钥（比如：`my-super-secret-key-12345`）

### 6. 部署
```bash
npx wrangler deploy
```

部署成功后会输出 Worker 的 URL，格式如：
```
https://bookmark-sync.你的子域名.workers.dev
```

### 7. 更新 Android 代码
把 Worker URL 填入 `CloudSyncManager.kt` 的 `BASE_URL` 常量中。
