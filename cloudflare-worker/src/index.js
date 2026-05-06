export default {
  async fetch(request, env) {
    const url = new URL(request.url);
    const path = url.pathname;

    // CORS
    if (request.method === 'OPTIONS') {
      return new Response(null, { headers: corsHeaders() });
    }

    try {
      // 注册
      if (path === '/api/register' && request.method === 'POST') {
        return handleRegister(request, env);
      }
      // 登录
      if (path === '/api/login' && request.method === 'POST') {
        return handleLogin(request, env);
      }
      // 上传书签（本地覆盖云端）
      if (path === '/api/bookmarks/upload' && request.method === 'POST') {
        return withAuth(request, env, handleUpload);
      }
      // 下载书签（云端覆盖本地）
      if (path === '/api/bookmarks/download' && request.method === 'GET') {
        return withAuth(request, env, handleDownload);
      }
      // 添加单条书签到云端
      if (path === '/api/bookmarks/add' && request.method === 'POST') {
        return withAuth(request, env, handleAddOne);
      }
      // 删除单条云端书签
      if (path.startsWith('/api/bookmarks/') && request.method === 'DELETE') {
        const id = path.split('/').pop();
        return withAuth(request, env, (req, env, userId) => handleDeleteOne(req, env, userId, id));
      }

      return json({ error: 'Not Found' }, 404);
    } catch (e) {
      return json({ error: e.message || 'Internal Error' }, 500);
    }
  }
};

// ==================== 认证 ====================

async function handleRegister(request, env) {
  const { username, password } = await request.json();
  if (!username || !password) {
    return json({ error: '用户名和密码不能为空' }, 400);
  }
  if (username.length < 2 || password.length < 4) {
    return json({ error: '用户名至少2位，密码至少4位' }, 400);
  }

  const existing = await env.DB.prepare('SELECT id FROM users WHERE username = ?').bind(username).first();
  if (existing) {
    return json({ error: '用户名已存在' }, 409);
  }

  const passwordHash = await hashPassword(password);
  const result = await env.DB.prepare('INSERT INTO users (username, password_hash) VALUES (?, ?)')
    .bind(username, passwordHash).run();

  const token = await generateToken(result.meta.last_row_id, username, env);
  return json({ token, username });
}

async function handleLogin(request, env) {
  const { username, password } = await request.json();
  if (!username || !password) {
    return json({ error: '用户名和密码不能为空' }, 400);
  }

  const user = await env.DB.prepare('SELECT id, password_hash FROM users WHERE username = ?').bind(username).first();
  if (!user) {
    return json({ error: '用户名或密码错误' }, 401);
  }

  const valid = await verifyPassword(password, user.password_hash);
  if (!valid) {
    return json({ error: '用户名或密码错误' }, 401);
  }

  const token = await generateToken(user.id, username, env);
  return json({ token, username });
}

// ==================== 书签操作 ====================

async function handleUpload(request, env, userId) {
  const { bookmarks } = await request.json();
  if (!Array.isArray(bookmarks)) {
    return json({ error: '无效的书签数据' }, 400);
  }

  // 清空该用户的所有书签
  await env.DB.prepare('DELETE FROM bookmarks WHERE user_id = ?').bind(userId).run();

  // 批量插入
  if (bookmarks.length > 0) {
    // D1 批量操作
    const stmts = bookmarks.map(b =>
      env.DB.prepare(
        'INSERT INTO bookmarks (user_id, title, url, is_folder, parent_id, position, favicon, created_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?)'
      ).bind(userId, b.title || '', b.url || null, b.isFolder ? 1 : 0, b.parentId != null ? b.parentId : null, b.position || 0, b.favicon || null, b.createdAt || Date.now())
    );

    // D1 batch 最多支持一次性执行多条
    await env.DB.batch(stmts);
  }

  return json({ success: true, count: bookmarks.length });
}

async function handleAddOne(request, env, userId) {
  const b = await request.json();
  if (!b.title && !b.url) {
    return json({ error: '书签数据不能为空' }, 400);
  }

  const result = await env.DB.prepare(
    'INSERT INTO bookmarks (user_id, title, url, is_folder, parent_id, position, favicon, created_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?)'
  ).bind(userId, b.title || '', b.url || null, b.isFolder ? 1 : 0, b.parentId != null ? b.parentId : null, b.position || 0, b.favicon || null, b.createdAt || Date.now()).run();

  return json({ success: true, id: result.meta.last_row_id });
}

async function handleDeleteOne(request, env, userId, bookmarkId) {
  const id = parseInt(bookmarkId);
  if (isNaN(id)) {
    return json({ error: '无效的书签ID' }, 400);
  }

  const result = await env.DB.prepare('DELETE FROM bookmarks WHERE id = ? AND user_id = ?')
    .bind(id, userId).run();

  if (result.meta.changes === 0) {
    return json({ error: '书签不存在或无权删除' }, 404);
  }

  return json({ success: true });
}

async function handleDownload(request, env, userId) {
  const { results } = await env.DB.prepare(
    'SELECT id, title, url, is_folder, parent_id, position, favicon, created_at FROM bookmarks WHERE user_id = ? ORDER BY is_folder DESC, position ASC'
  ).bind(userId).all();

  const bookmarks = results.map(r => ({
    id: r.id,
    title: r.title,
    url: r.url,
    isFolder: r.is_folder === 1,
    parentId: r.parent_id,
    position: r.position,
    favicon: r.favicon,
    createdAt: r.created_at
  }));

  return json({ bookmarks });
}

// ==================== 中间件 ====================

async function withAuth(request, env, handler) {
  const authHeader = request.headers.get('Authorization');
  if (!authHeader || !authHeader.startsWith('Bearer ')) {
    return json({ error: '未登录' }, 401);
  }

  const token = authHeader.substring(7);
  const payload = await verifyToken(token, env);
  if (!payload) {
    return json({ error: 'token无效或已过期' }, 401);
  }

  return handler(request, env, payload.userId);
}

// ==================== 工具函数 ====================

async function hashPassword(password) {
  const encoder = new TextEncoder();
  const data = encoder.encode(password + 'bookmark-sync-salt');
  const hash = await crypto.subtle.digest('SHA-256', data);
  return btoa(String.fromCharCode(...new Uint8Array(hash)));
}

async function verifyPassword(password, hash) {
  const computed = await hashPassword(password);
  return computed === hash;
}

async function generateToken(userId, username, env) {
  const header = btoa(JSON.stringify({ alg: 'HS256', typ: 'JWT' }));
  const payload = btoa(JSON.stringify({
    userId,
    username,
    exp: Date.now() + 30 * 24 * 60 * 60 * 1000 // 30天过期
  }));
  const signature = await sign(`${header}.${payload}`, env);
  return `${header}.${payload}.${signature}`;
}

async function verifyToken(token, env) {
  try {
    const [header, payload, signature] = token.split('.');
    const expectedSig = await sign(`${header}.${payload}`, env);
    if (signature !== expectedSig) return null;

    const data = JSON.parse(atob(payload));
    if (data.exp < Date.now()) return null;
    return data;
  } catch {
    return null;
  }
}

async function sign(data, env) {
  const secret = env.JWT_SECRET || 'default-secret-change-me';
  const encoder = new TextEncoder();
  const key = await crypto.subtle.importKey(
    'raw', encoder.encode(secret), { name: 'HMAC', hash: 'SHA-256' }, false, ['sign']
  );
  const sig = await crypto.subtle.sign('HMAC', key, encoder.encode(data));
  return btoa(String.fromCharCode(...new Uint8Array(sig)));
}

function json(data, status = 200) {
  return new Response(JSON.stringify(data), {
    status,
    headers: { 'Content-Type': 'application/json', ...corsHeaders() }
  });
}

function corsHeaders() {
  return {
    'Access-Control-Allow-Origin': '*',
    'Access-Control-Allow-Methods': 'GET, POST, DELETE, OPTIONS',
    'Access-Control-Allow-Headers': 'Content-Type, Authorization'
  };
}
