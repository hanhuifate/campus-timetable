/**
 * 校园课表网页版 · 本地小后端
 *
 * 职责：
 *   1. 代用户完成学校统一身份认证（CAS）登录，把 Cookie 存在服务端会话里；
 *   2. 用该会话请求 jwglxt 的课表接口（kbList）并解析成前端使用的课程结构；
 *   3. 托管 public/ 下的静态页面。
 *
 * 依赖只有 Node 18+ 自带模块（fetch / crypto / http），不需要 npm install。
 * 启动：node server.js       默认 http://127.0.0.1:8787
 */
'use strict';

const http = require('node:http');
const fs = require('node:fs');
const path = require('node:path');
const crypto = require('node:crypto');

const PORT = Number(process.env.PORT || 8787);
const HOST = process.env.HOST || '127.0.0.1';
const BASE = 'https://jwxt.njfu.edu.cn';
const CAS = 'https://uia.njfu.edu.cn/authserver/login';
const SERVICE = 'http://jwxt.njfu.edu.cn/';
const UA =
  'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 ' +
  '(KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36';

/** sid -> { jar: Map<string,string>, user: string, updatedAt: number } */
const sessions = new Map();
const SID_COOKIE = 'ct_sid';

const MIME = {
  '.html': 'text/html; charset=utf-8',
  '.js': 'text/javascript; charset=utf-8',
  '.css': 'text/css; charset=utf-8',
  '.json': 'application/json; charset=utf-8',
  '.webmanifest': 'application/manifest+json; charset=utf-8',
  '.svg': 'image/svg+xml',
  '.png': 'image/png',
  '.ico': 'image/x-icon'
};

/* ------------------------------------------------------------------ 会话 */

function newSession() {
  const sid = crypto.randomBytes(24).toString('hex');
  sessions.set(sid, { jar: new Map(), user: '', updatedAt: Date.now() });
  return sid;
}

function sessionOf(req) {
  const raw = req.headers.cookie || '';
  for (const part of raw.split(';')) {
    const [k, ...rest] = part.trim().split('=');
    if (k === SID_COOKIE) {
      const s = sessions.get(rest.join('='));
      if (s) {
        s.updatedAt = Date.now();
        return s;
      }
    }
  }
  return null;
}

/** 30 分钟没人用就清掉，避免内存里堆着过期 Cookie。 */
setInterval(() => {
  const now = Date.now();
  for (const [sid, s] of sessions) {
    if (now - s.updatedAt > 30 * 60 * 1000) sessions.delete(sid);
  }
}, 5 * 60 * 1000).unref();

function cookieHeader(session) {
  return [...session.jar.entries()].map(([k, v]) => `${k}=${v}`).join('; ');
}

function absorb(session, res) {
  const list = typeof res.headers.getSetCookie === 'function'
    ? res.headers.getSetCookie()
    : [];
  for (const raw of list) {
    const first = raw.split(';')[0];
    const idx = first.indexOf('=');
    if (idx <= 0) continue;
    const name = first.slice(0, idx).trim();
    const value = first.slice(idx + 1).trim();
    const dead = value === 'deleted' || /Max-Age=0/i.test(raw) || raw.includes('1970');
    if (dead) session.jar.delete(name);
    else session.jar.set(name, value);
  }
}

async function request(session, url, opts = {}) {
  const headers = Object.assign({
    'User-Agent': UA,
    'Accept-Language': 'zh-CN,zh;q=0.9'
  }, opts.headers || {});
  const ck = cookieHeader(session);
  if (ck) headers.Cookie = ck;
  const res = await fetch(url, {
    method: opts.method || 'GET',
    headers,
    body: opts.body,
    redirect: 'manual',
    signal: AbortSignal.timeout(opts.timeout || 15000)
  });
  absorb(session, res);
  return res;
}

function resolveUrl(base, location) {
  try {
    return new URL(location, base).toString();
  } catch {
    return location;
  }
}

/* ------------------------------------------------------------------ CAS */

/** 金智 CAS 页面里的隐藏字段：lt / execution 等；加密盐藏在一个只有 id 的 input 里。 */
function parseHidden(html) {
  const out = {};
  const re = /<input[^>]+>/gi;
  for (const tag of html.match(re) || []) {
    const name = /\bname\s*=\s*["']([^"']+)["']/i.exec(tag);
    const id = /\bid\s*=\s*["']([^"']+)["']/i.exec(tag);
    const value = /\bvalue\s*=\s*["']([^"']*)["']/i.exec(tag);
    if (name && /type\s*=\s*["']hidden["']/i.test(tag)) {
      out[name[1]] = value ? value[1] : '';
    }
    if (id && id[1] === 'pwdDefaultEncryptSalt') {
      out.pwdDefaultEncryptSalt = value ? value[1] : '';
    }
  }
  return out;
}

function randomString(n) {
  const chars = 'abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789';
  let s = '';
  for (let i = 0; i < n; i++) s += chars[crypto.randomInt(chars.length)];
  return s;
}

/** 与学校登录页一致的密码加密：AES-128-CBC，密钥由 PBKDF2(salt, iv) 派生。 */
function encryptPassword(password, salt) {
  const iv = randomString(16);
  const key = crypto.pbkdf2Sync(salt, iv, 1000, 16, 'sha1');
  const cipher = crypto.createCipheriv('aes-128-cbc', key, Buffer.from(iv, 'utf8'));
  const enc = cipher.update(password, 'utf8', 'base64') + cipher.final('base64');
  return iv + enc;
}

function pageHint(html) {
  const text = html
    .replace(/<script[\s\S]*?<\/script>/gi, ' ')
    .replace(/<style[\s\S]*?<\/style>/gi, ' ')
    .replace(/<[^>]+>/g, ' ')
    .replace(/&nbsp;/g, ' ')
    .replace(/\s+/g, ' ')
    .trim();
  const known = [
    '您提供的用户名或者密码有误',
    '用户名或密码错误',
    '账号或密码错误',
    '密码错误',
    '账号已被锁定',
    '账号不存在',
    '用户不存在'
  ];
  for (const item of known) if (text.includes(item)) return item;
  const captcha = /验证码[^。，]{0,12}(?:错误|不正确|失效|已过期)/.exec(text);
  if (captcha) return '学校登录页启用了验证码，自动登录走不通，请改用“粘贴 Cookie”';
  return '';
}

async function casLogin(username, password) {
  const session = { jar: new Map() };
  const loginUrl = `${CAS}?service=${encodeURIComponent(SERVICE)}`;

  let res = await request(session, loginUrl);
  const html = await res.text();
  const hidden = parseHidden(html);
  if (!hidden.lt || !hidden.execution) {
    throw new Error('没能拿到登录页参数（lt / execution），学校登录页可能改版了');
  }

  const form = new URLSearchParams({
    username,
    password: hidden.pwdDefaultEncryptSalt
      ? encryptPassword(password, hidden.pwdDefaultEncryptSalt)
      : password,
    lt: hidden.lt,
    // 隐藏字段里给的是 dynamicLogin，账号密码登录必须显式改成这个值
    dllt: 'userNamePasswordLogin',
    execution: hidden.execution,
    _eventId: 'submit',
    rmShown: '1'
  });

  res = await request(session, loginUrl, {
    method: 'POST',
    headers: {
      'Content-Type': 'application/x-www-form-urlencoded; charset=UTF-8',
      Referer: loginUrl,
      Origin: 'https://uia.njfu.edu.cn'
    },
    body: form.toString()
  });

  // 登录成功后 CAS 会 302 到带 ticket 的服务地址，再落地到 jwxt
  let url = loginUrl;
  let hops = 0;
  while (res.status >= 300 && res.status < 400 && hops < 8) {
    const loc = res.headers.get('location');
    if (!loc) break;
    url = resolveUrl(url, loc);
    res = await request(session, url);
    hops++;
  }
  if (res.status >= 300 && res.status < 400) {
    throw new Error('登录重定向次数过多，学校可能加了风控');
  }

  const body = await res.text();
  const hasJwxt = [...session.jar.keys()].some((k) => /JSESSIONID/i.test(k));
  if (!hasJwxt || /authserver\/login/i.test(url)) {
    const hint = pageHint(body);
    throw new Error(hint || '登录失败：账号或密码不正确');
  }
  return session;
}

/* ------------------------------------------------------------ 课表抓取 */

function termOf(termStart) {
  const month = Number(String(termStart).slice(5, 7)) || 9;
  const year = Number(String(termStart).slice(0, 4)) || new Date().getFullYear();
  return month >= 1 && month <= 7
    ? { xnm: String(year - 1), xqm: '12' }
    : { xnm: String(year), xqm: '3' };
}

async function fetchKb(session, xnm, xqm, user) {
  const api = `${BASE}/jwglxt/kbcx/xskbcx_cxXsKb.html?gnmkdm=N2151&su=${encodeURIComponent(user)}`;
  const res = await request(session, api, {
    method: 'POST',
    headers: {
      'Content-Type': 'application/x-www-form-urlencoded; charset=UTF-8',
      'X-Requested-With': 'XMLHttpRequest',
      Referer: `${BASE}/jwglxt/kbcx/xskbcx_cxXsKb.html?gnmkdm=N2151`,
      Accept: 'application/json, text/javascript, */*; q=0.01'
    },
    body: `xnm=${xnm}&xqm=${xqm}&kzlx=ck&xsdm=`
  });
  const location = res.headers.get('location') || '';
  if (res.status >= 300 && res.status < 400) {
    return { needLogin: /authserver/i.test(location), json: null, url: resolveUrl(api, location) };
  }
  const text = (await res.text()).replace(/^\uFEFF/, '').trim();
  if (!text.startsWith('{')) {
    return { needLogin: /authserver|统一身份认证/i.test(text), json: null, url: api };
  }
  try {
    return { needLogin: false, json: JSON.parse(text), url: api };
  } catch {
    return { needLogin: false, json: null, url: api };
  }
}

const NAME_RE = /^[\u4e00-\u9fa5·]{2,5}$/;

function teacherOf(item) {
  const raw = String(item.jsxx || '').trim();
  if (raw) {
    const names = raw
      .split(/[,，;；]/)
      .map((part) => part.split(/[\/／]/).map((s) => s.trim()).find((s) => NAME_RE.test(s)))
      .filter(Boolean);
    if (names.length) return [...new Set(names)].join('、');
  }
  return String(item.xm || '').trim().replace(/,/g, '、').replace(/\.\./g, '…');
}

function weeksOf(text) {
  const weeks = new Set();
  const re = /(\d{1,2})\s*(?:[-~～]\s*(\d{1,2}))?\s*周/g;
  let m;
  while ((m = re.exec(text))) {
    const a = Number(m[1]);
    const b = m[2] === undefined ? a : Number(m[2]);
    if (a >= 1 && a <= 30 && b >= a && b <= 30) for (let w = a; w <= b; w++) weeks.add(w);
  }
  if (!weeks.size) return '';
  const odd = /单周|[(（]单[)）]/.test(text);
  const even = /双周|[(（]双[)）]/.test(text);
  let list = [...weeks].sort((a, b) => a - b);
  if (odd && !even) list = list.filter((w) => w % 2 === 1);
  if (even && !odd) list = list.filter((w) => w % 2 === 0);
  return weeksToText(list);
}

function weeksToText(list) {
  if (!list.length) return '';
  const out = [];
  let start = list[0];
  let prev = list[0];
  for (let i = 1; i < list.length; i++) {
    const cur = list[i];
    if (cur === prev + 1) {
      prev = cur;
    } else {
      out.push(start === prev ? `${start}` : `${start}-${prev}`);
      start = prev = cur;
    }
  }
  out.push(start === prev ? `${start}` : `${start}-${prev}`);
  return out.join(',');
}

function parseCourses(json) {
  const list = (json && json.kbList) || [];
  const seen = new Set();
  const out = [];
  for (const item of list) {
    const name = String(item.kcmc || '').trim();
    if (!name) continue;
    const day = Number(String(item.xqj || '').trim());
    if (!(day >= 1 && day <= 7)) continue;
    const sectionText = String(item.jcs || item.jc || '');
    const numbers = [...sectionText.matchAll(/\d{1,2}/g)].map((m) => Number(m[0]));
    if (!numbers.length) continue;
    const start = Math.min(Math.max(numbers[0], 1), 12);
    const end = Math.min(Math.max(numbers[numbers.length - 1], start), 12);
    const weeks = weeksOf(String(item.zcd || ''));
    if (!weeks) continue;
    const teacher = teacherOf(item);
    const room = String(item.cdmc || '').trim();
    const key = `${name}|${teacher}|${room}|${day}|${start}|${end}|${weeks}`;
    if (seen.has(key)) continue;
    seen.add(key);
    out.push({
      name,
      teacher,
      room,
      day,
      start,
      end,
      weeks,
      color: Math.abs(hashCode(name)) % 8,
      id: crypto.createHash('md5').update(key).digest('hex').slice(0, 12),
      custom: false
    });
  }
  out.sort((a, b) => a.day - b.day || a.start - b.start || a.name.localeCompare(b.name, 'zh'));
  return out;
}

function hashCode(text) {
  let h = 0;
  for (const ch of text) h = (h * 31 + ch.codePointAt(0)) | 0;
  return h;
}

async function syncTimetable(session, user, termStart) {
  const { xnm, xqm } = termOf(termStart || '2026-08-31');
  const candidates = [
    { xnm, xqm },
    { xnm, xqm: xqm === '3' ? '12' : '3' },
    { xnm: String(Number(xnm) - 1), xqm: '12' },
    { xnm: String(Number(xnm) - 1), xqm: '3' }
  ];
  for (const t of candidates) {
    const got = await fetchKb(session, t.xnm, t.xqm, user);
    if (got.needLogin) {
      const err = new Error('教务会话已失效，请重新登录');
      err.code = 'NEED_LOGIN';
      throw err;
    }
    const courses = got.json ? parseCourses(got.json) : [];
    if (courses.length) return { courses, term: t };
  }
  throw new Error('教务没有返回课表数据（可能本学期参数不对，或账号没有课表）');
}

/* --------------------------------------------------------------- 服务端 */

function json(res, code, data) {
  const body = JSON.stringify(data);
  res.writeHead(code, {
    'Content-Type': 'application/json; charset=utf-8',
    'Cache-Control': 'no-store'
  });
  res.end(body);
}

async function readBody(req) {
  let raw = '';
  for await (const chunk of req) {
    raw += chunk;
    if (raw.length > 1_000_000) throw new Error('请求过大');
  }
  if (!raw) return {};
  try {
    return JSON.parse(raw);
  } catch {
    return {};
  }
}

function serveStatic(req, res) {
  const url = new URL(req.url, `http://${req.headers.host}`);
  const rel = url.pathname === '/' ? '/index.html' : url.pathname;
  const file = path.join(__dirname, 'public', path.normalize(rel).replace(/^([.][.][\/\\])+/, ''));
  if (!file.startsWith(path.join(__dirname, 'public'))) {
    res.writeHead(403).end('forbidden');
    return;
  }
  fs.readFile(file, (err, data) => {
    if (err) {
      res.writeHead(404, { 'Content-Type': 'text/plain; charset=utf-8' }).end('页面不存在');
      return;
    }
    res.writeHead(200, {
      'Content-Type': MIME[path.extname(file)] || 'application/octet-stream',
      'Cache-Control': 'no-cache'
    });
    res.end(data);
  });
}

const server = http.createServer(async (req, res) => {
  const url = new URL(req.url, `http://${req.headers.host}`);
  try {
    if (url.pathname === '/api/me') {
      const s = sessionOf(req);
      return json(res, 200, {
        loggedIn: !!(s && s.jar.size),
        user: s ? s.user : ''
      });
    }

    if (url.pathname === '/api/logout') {
      const sid = cookieSid(req);
      if (sid) sessions.delete(sid);
      res.writeHead(200, {
        'Content-Type': 'application/json; charset=utf-8',
        'Set-Cookie': `${SID_COOKIE}=; Path=/; HttpOnly; SameSite=Lax; Max-Age=0`
      });
      return res.end('{"ok":true}');
    }

    if (url.pathname === '/api/login' && req.method === 'POST') {
      const { username = '', password = '', termStart = '', user = '' } = await readBody(req);
      if (!username || !password) return json(res, 400, { error: '请填写学号和密码' });
      const fresh = await casLogin(username.trim(), password);
      const sid = newSession();
      const stored = sessions.get(sid);
      stored.jar = fresh.jar;
      stored.user = user || username.trim();
      const result = await syncTimetable(stored, username.trim(), termStart);
      res.setHeader('Set-Cookie', `${SID_COOKIE}=${sid}; Path=/; HttpOnly; SameSite=Lax; Max-Age=86400`);
      return json(res, 200, { ok: true, user: username.trim(), ...result });
    }

    if (url.pathname === '/api/cookie' && req.method === 'POST') {
      // 兜底：自动登录被风控挡住时，用户可从浏览器复制 Cookie 粘进来
      const { cookie = '', user = '', termStart = '' } = await readBody(req);
      if (!cookie) return json(res, 400, { error: '请粘贴 Cookie' });
      let sid = cookieSid(req);
      if (!sid || !sessions.has(sid)) {
        sid = newSession();
        res.setHeader('Set-Cookie', `${SID_COOKIE}=${sid}; Path=/; HttpOnly; SameSite=Lax; Max-Age=86400`);
      }
      const s = sessions.get(sid);
      for (const part of cookie.split(';')) {
        const idx = part.indexOf('=');
        if (idx <= 0) continue;
        s.jar.set(part.slice(0, idx).trim(), part.slice(idx + 1).trim());
      }
      s.user = user.trim();
      const result = await syncTimetable(s, user.trim(), termStart);
      return json(res, 200, { ok: true, ...result });
    }

    if (url.pathname === '/api/timetable' && req.method === 'POST') {
      const s = sessionOf(req);
      if (!s || !s.jar.size) return json(res, 401, { error: '还没登录', code: 'NEED_LOGIN' });
      const { termStart = '' } = await readBody(req);
      const result = await syncTimetable(s, s.user, termStart);
      return json(res, 200, { ok: true, user: s.user, ...result });
    }

    if (url.pathname.startsWith('/api/')) return json(res, 404, { error: '接口不存在' });

    serveStatic(req, res);
  } catch (err) {
    const needLogin = err && err.code === 'NEED_LOGIN';
    json(res, needLogin ? 401 : 500, {
      error: (err && err.message) || '服务出错',
      code: needLogin ? 'NEED_LOGIN' : 'ERROR'
    });
  }
});

function cookieSid(req) {
  const raw = req.headers.cookie || '';
  for (const part of raw.split(';')) {
    const [k, ...rest] = part.trim().split('=');
    if (k === SID_COOKIE) return rest.join('=');
  }
  return '';
}

server.listen(PORT, HOST, () => {
  console.log(`课表网页版已启动： http://${HOST}:${PORT}`);
  console.log('提示：只在本机/局域网使用，Cookie 保存在内存里，重启即失效。');
});
