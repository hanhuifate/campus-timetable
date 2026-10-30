/**
 * 校园课表网页版 · 本地小后端
 *
 * 职责：
 *   1. 代用户完成学校统一身份认证（CAS）登录，把 Cookie 存在服务端会话里；
 *   2. 用该会话请求 jwglxt 的课表接口（kbList）并解析成前端使用的课程结构；
 *   3. 托管 public/ 下的静态页面。
 *
 * 依赖只有 Node 18+ 自带模块（fetch / crypto / http），不需要 npm install。
 * 启动：node server.js       本机 http://127.0.0.1:8787，同网段手机用 http://<本机IP>:8787
 * 云托管：监听 $PORT（Render/Railway 自动注入），会话 Cookie 自动加 Secure。
 */
'use strict';

const http = require('node:http');
const fs = require('node:fs');
const os = require('node:os');
const path = require('node:path');
const crypto = require('node:crypto');

const PORT = Number(process.env.PORT || 8787);
const HOST = process.env.HOST || '0.0.0.0';
const BASE = process.env.CT_BASE || 'https://jwxt.njfu.edu.cn';
const CAS = process.env.CT_CAS || 'https://uia.njfu.edu.cn/authserver/login';
const SERVICE = process.env.CT_SERVICE || 'http://jwxt.njfu.edu.cn/';
const DEBUG = !!process.env.CT_DEBUG;
const HOSTED = !!(process.env.RENDER || process.env.RAILWAY_ENVIRONMENT || process.env.CT_HOSTED);
const JWXT_HOST = new URL(BASE).hostname;
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

/**
 * Cookie 罐按「域|名字」存。
 * 统一认证（uia）和教务（jwxt）都会下发同名的 JSESSIONID，
 * 只按名字存会被后写的覆盖，导致认证过了但教务会话认不出来。
 * jar: Map<"host|name", value>
 */
function cookieList(res) {
  if (typeof res.headers.getSetCookie === 'function') return res.headers.getSetCookie();
  const raw = res.headers.get('set-cookie');
  return raw ? [raw] : [];
}

function absorb(session, res, url) {
  let host = '';
  try { host = new URL(url).hostname.toLowerCase(); } catch { /* 忽略 */ }
  for (const raw of cookieList(res)) {
    const first = raw.split(';')[0];
    const idx = first.indexOf('=');
    if (idx <= 0) continue;
    const name = first.slice(0, idx).trim();
    const value = first.slice(idx + 1).trim();
    const domainAttr = /\bdomain=([^;]+)/i.exec(raw);
    const domain = domainAttr
      ? domainAttr[1].trim().toLowerCase().replace(/^\./, '')
      : host;
    const key = `${domain}|${name}`;
    const dead = value === 'deleted' || /Max-Age=0/i.test(raw) || raw.includes('1970');
    if (dead) session.jar.delete(key);
    else session.jar.set(key, value);
  }
}

function cookieHeader(session, url) {
  let host = '';
  try { host = new URL(url).hostname.toLowerCase(); } catch { /* 忽略 */ }
  const parts = [];
  for (const [key, value] of session.jar) {
    const sep = key.indexOf('|');
    if (sep <= 0) continue;
    const domain = key.slice(0, sep);
    if (domain === host || host.endsWith(`.${domain}`)) {
      parts.push(`${key.slice(sep + 1)}=${value}`);
    }
  }
  return parts.join('; ');
}

/** 教务域下有会话 Cookie 才算真的登进来了（jsxsd 用 bzb_jsxsd，jwglxt 用 JSESSIONID）。 */
function hasJwxtSession(session) {
  for (const key of session.jar.keys()) {
    const sep = key.indexOf('|');
    const name = sep > 0 ? key.slice(sep + 1) : '';
    if (sep > 0 && key.slice(0, sep) === JWXT_HOST && /^(?:JSESSIONID|bzb_jsxsd)$/i.test(name)) {
      return true;
    }
  }
  return false;
}

async function request(session, url, opts = {}) {
  const headers = Object.assign({
    'User-Agent': UA,
    'Accept-Language': 'zh-CN,zh;q=0.9'
  }, opts.headers || {});
  const ck = cookieHeader(session, url);
  if (ck) headers.Cookie = ck;
  const res = await fetch(url, {
    method: opts.method || 'GET',
    headers,
    body: opts.body,
    redirect: 'manual',
    signal: AbortSignal.timeout(opts.timeout || 15000)
  });
  absorb(session, res, url);
  if (DEBUG) {
    const names = cookieList(res).map((c) => c.split('=')[0]).join(',');
    console.log(
      `[req] ${opts.method || 'GET'} ${url} -> ${res.status}` +
      `${res.headers.get('location') ? ' loc=' + res.headers.get('location') : ''}` +
      `${names ? ' set-cookie=' + names : ''}`
    );
  }
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

/** 学校 login.js 用的随机字符集（_rds）。 */
const RANDOM_CHARS = 'ABCDEFGHJKMNPQRSTWXYZabcdefhijkmnprstwxyz2345678';
/** 服务端能接受的固定 IV（浏览器脚本里是随机 16 字符，但服务端并不校验 IV）。 */
const AES_IV = 'HDbk7NdBpFPpFrZR';

function randomChars(n) {
  let s = '';
  for (let i = 0; i < n; i++) s += RANDOM_CHARS[crypto.randomInt(RANDOM_CHARS.length)];
  return s;
}

/**
 * 提交给学校 CAS 的密码密文：
 *   key   = 页面里的 pwdDefaultEncryptSalt（直接当 AES-128 密钥）
 *   iv    = 固定 16 字节（服务端接受任意 16 字节，随机 IV 会导致校验失败）
 *   明文  = 随机 64 字符前缀 + 原始密码
 *   结果  = AES-128-CBC / PKCS7 的 base64 密文（不带 IV 前缀）
 * 与学校 encrypt.js 的明文结构一致，差异只在 IV 取值。
 */
function encryptPassword(password, salt) {
  const key = Buffer.from(String(salt).trim(), 'utf8');
  if (key.length !== 16) throw new Error('盐长度异常');
  const prefix = randomChars(64);
  const cipher = crypto.createCipheriv('aes-128-cbc', key, Buffer.from(AES_IV, 'utf8'));
  return cipher.update(prefix + password, 'utf8', 'base64') + cipher.final('base64');
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

/** 提交前学校页面会先问 needCaptcha.html；返回 true 表示这个账号此刻必须图形验证码。 */
async function needsCaptcha(session, username, loginUrl) {
  if (!username) return false;
  const base = CAS.replace(/\/login$/, '');
  const url =
    `${base}/needCaptcha.html?username=${encodeURIComponent(username)}` +
    `&pwdEncrypt2=pwdEncryptSalt&_=${Date.now()}`;
  try {
    const res = await request(session, url, {
      headers: {
        'X-Requested-With': 'XMLHttpRequest',
        Referer: loginUrl,
        Accept: '*/*'
      }
    });
    const text = (await res.text()).trim();
    return text.startsWith('true');
  } catch {
    return false;
  }
}

/**
 * 教务换票。教务的 http 地址会被 301 到 https，直接走 https，
 * 少一次可能把票据消耗掉的跳转。
 */
async function landTicket(session, ticketUrl) {
  let url = ticketUrl;
  try {
    const u = new URL(ticketUrl);
    // 只把教务自己的 http 地址升成 https，别的地址保持原样
    if (u.protocol === 'http:' && u.hostname === JWXT_HOST) u.protocol = 'https:';
    url = u.toString();
  } catch { /* 保持原样 */ }

  const headers = { Accept: 'text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8' };
  let res = await request(session, url, { headers });
  let hops = 0;
  while (res.status >= 300 && res.status < 400 && hops < 8) {
    const loc = res.headers.get('location');
    if (!loc) break;
    url = resolveUrl(url, loc);
    res = await request(session, url, { headers });
    hops++;
  }
  return { status: res.status, url, body: await res.text() };
}

/** 用登录时拿到的 CASTGC 再换一张新票据（票据只能用一次，重试要换新的）。 */
async function silentTicket(session) {
  const casHost = new URL(CAS).hostname;
  const hasCastgc = [...session.jar.keys()].some((k) => k.startsWith(`${casHost}|CASTGC`));
  if (!hasCastgc) return '';
  const res = await request(session, `${CAS}?service=${encodeURIComponent(SERVICE)}`);
  const text = await res.text();
  if (DEBUG) console.log(`[ticket] 静默换票 ${res.status} len=${text.length}`);
  return res.status >= 300 && res.status < 400 ? res.headers.get('location') || '' : '';
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

  if (await needsCaptcha(session, username, loginUrl)) {
    const err = new Error(
      '学校要求这个账号输入图形验证码（通常是之前密码试错几次后触发的）。' +
        '请先在浏览器里登录一次教务把验证码过掉，再回来重试；或直接用「粘贴 Cookie」同步。'
    );
    err.code = 'NEED_CAPTCHA';
    throw err;
  }

  let encoded = password;
  if (hidden.pwdDefaultEncryptSalt) {
    try {
      encoded = encryptPassword(password, hidden.pwdDefaultEncryptSalt);
    } catch {
      encoded = password;
    }
  }

  const form = new URLSearchParams({
    username,
    password: encoded,
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
      Origin: new URL(CAS).origin
    },
    body: form.toString()
  });

  // 登录成功 CAS 会 302 到带 ticket 的教务地址；没跳转就是没登录成功
  if (res.status < 300 || res.status >= 400) {
    const body = await res.text();
    const err = new Error(pageHint(body) || '登录失败：账号或密码不正确');
    err.code = 'BAD_CREDENTIALS';
    throw err;
  }

  const location = res.headers.get('location') || '';
  await res.text();
  if (!location) throw new Error('登录后没有返回跳转地址，学校登录流程可能改版了');

  let landed = await landTicket(session, location);
  // 票据是一次性的，换票失败就用 CASTGC 再换一张新票重试
  if (!hasJwxtSession(session)) {
    const again = await silentTicket(session);
    if (again) landed = await landTicket(session, again);
  }

  if (DEBUG) {
    console.log(`[login] 落地 ${landed.status} ${landed.url}，jar=${[...session.jar.keys()].join(',')}`);
  }

  if (!hasJwxtSession(session)) {
    const hint = pageHint(landed.body);
    if (hint) throw new Error(hint);
    const err = new Error(
      `教务换票失败（HTTP ${landed.status}）。学校教务暂时没接受自动登录的票据，` +
        '可以改用「粘贴 Cookie」同步：在浏览器登录教务后，把请求头里的 Cookie 粘过来。'
    );
    err.code = 'TICKET_FAILED';
    throw err;
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

/* ------------------------------------------ jsxsd（南林实际在用的教务页面） */

const JSXSD_HOME = `${BASE}/jsxsd/framework/xsMainV.jsp`;
const JSXSD_FRAME = `${BASE}/jsxsd/framework/xsMainV_new.htmlx?t1=1`;
const JSXSD_KB = `${BASE}/jsxsd/framework/mainV_index_loadkb.htmlx`;

/** 调试时把抓到的页面存到 %TEMP%\ct-html，方便按真实结构核对解析。 */
function dumpHtml(name, html) {
  if (!DEBUG) return;
  try {
    const dir = path.join(process.env.TEMP || __dirname, 'ct-html');
    fs.mkdirSync(dir, { recursive: true });
    const file = path.join(dir, name);
    fs.writeFileSync(file, html, 'utf8');
    console.log(`[dump] ${file} (${html.length} 字符)`);
  } catch (e) {
    console.log(`[dump] 失败：${e.message}`);
  }
}

function stripTags(html) {
  return html
    .replace(/<script[\s\S]*?<\/script>/gi, ' ')
    .replace(/<style[\s\S]*?<\/style>/gi, ' ')
    .replace(/<[^>]+>/g, ' ')
    .replace(/&nbsp;/g, ' ')
    .replace(/&amp;/g, '&')
    .replace(/\s+/g, ' ')
    .trim();
}

/**
 * 课表要的两个参数都在框架页里：
 *   学期 = 脚本里写死的 "&xnxqid="+"2026-2027-1&xswk="（option 的 value 是空的，不能按 value 取）
 *   节次模式 = 当前选中的 <li name="kbjcmsid" class="layui-this"> 的 data-value
 */
function jsxsdParams(html) {
  const term =
    (/xnxqid="\s*\+\s*"\s*([^&"']+)/.exec(html) || [])[1] ||
    (/<option[^>]*>\s*(\d{4}-\d{4}-\d)\s*</.exec(html) || [])[1] ||
    '';
  const tags = [...html.matchAll(/<li[^>]*\bname\s*=\s*["']kbjcmsid["'][^>]*>/gi)].map((m) => m[0]);
  const chosen = tags.find((t) => /layui-this/i.test(t)) || tags[0] || '';
  const msid = (/data-value\s*=\s*["']([^"']+)["']/i.exec(chosen) || [])[1] || '';
  return { term: term.trim(), msid };
}

/**
 * 教务首页（xsMainV.jsp）只是个人中心，课表在 xsMainV_new 框架里 AJAX 加载：
 *   /jsxsd/framework/mainV_index_loadkb.htmlx?rq=all&sjmsValue=..&xnxqid=..&xswk=false
 */
async function fetchJsxsd(session) {
  const res = await request(session, JSXSD_FRAME, { headers: { Referer: JSXSD_HOME } });
  const frame = await res.text();
  dumpHtml('jsxsd-frame.html', frame);
  // 没进到课表框架页（被踢回登录页 / 会话失效）
  if (res.status !== 200 || !/kbjcmsid/i.test(frame)) return null;

  const { term, msid } = jsxsdParams(frame);
  if (!msid) {
    if (DEBUG) console.log('[jsxsd] 框架页里没找到节次模式 id');
    return null;
  }

  const url =
    `${JSXSD_KB}?rq=all&sjmsValue=${encodeURIComponent(msid)}` +
    `&xnxqid=${encodeURIComponent(term)}&xswk=false`;
  const kbRes = await request(session, url, {
    headers: {
      'X-Requested-With': 'XMLHttpRequest',
      Referer: JSXSD_FRAME,
      Accept: 'text/html, */*; q=0.01'
    }
  });
  const kb = await kbRes.text();
  dumpHtml('jsxsd-kb.html', kb);
  if (DEBUG) console.log(`[jsxsd] 课表接口 ${kbRes.status}，学期=${term} 模式=${msid}，${kb.length} 字符`);
  return kbRes.status === 200 ? kb : null;
}

/** 节次：优先 “01~02节”，其次 “第1-2节”，都没有就按表格行号。 */
function sectionsOf(text, rowIndex) {
  const twoDigit = (SECTION_RE.exec(text) || [''])[0];
  const numbers = [...twoDigit.matchAll(/\d{1,2}/g)].map((m) => Number(m[0]));
  let start = numbers.length ? numbers[0] : 0;
  let end = numbers.length ? numbers[numbers.length - 1] : 0;
  if (!numbers.length) {
    const alt = /第?\s*(\d{1,2})\s*[-~,，]\s*(\d{1,2})\s*节/.exec(text);
    if (alt) {
      start = Number(alt[1]);
      end = Number(alt[2]);
    }
  }
  if (!start) {
    start = rowIndex;
    end = rowIndex;
  }
  start = Math.min(Math.max(start, 1), 12);
  end = Math.min(Math.max(end, start), 12);
  return { start, end };
}

/**
 * 一格里的 .item-box（同一格常常叠着不同周次的多门课）。
 * 真实结构是每门课一个 <span class="box">（悬浮提示，含教师）+ 一个 <div class="item-box">（详情）。
 */
function itemBoxes(cellHtml) {
  return cellHtml.split(/<div[^>]*\bclass\s*=\s*["'][^"']*item-box[^"']*["'][^>]*>/i).slice(1);
}

/** 一门课从 <span class="box"> 开始（提示层里才有“教师：xxx”）。 */
function courseUnits(cellHtml) {
  const units = cellHtml.split(/<span[^>]*\bclass\s*=\s*["'][^"']*\bbox\b[^"']*["'][^>]*>/i).slice(1);
  return units.length ? units : [cellHtml];
}

const TCH_DIV_RE = /<div[^>]*\bclass\s*=\s*["'][^"']*tch-name[^"']*["'][^>]*>([\s\S]*?)<\/div>/i;

/** 一个 item-box 里的课：名字在 <p>，学分/节次在 tch-name，地点/周次在紧跟的 div。 */
function boxCourses(boxHtml) {
  const meta = stripTags(boxHtml);
  const body = boxHtml.replace(new RegExp(TCH_DIV_RE.source, 'gi'), ' '); // tch-name 里是“学分/节次”，不是地点
  const seg = stripTags(body);
  return boxHtml
    .split(/<p[^>]*>/i)
    .slice(1)
    .map((part) => {
      const cut = part.search(/<\/p>/i);
      return { name: stripTags(cut >= 0 ? part.slice(0, cut) : part), seg, meta };
    })
    .filter((it) => it.name);
}

const WEEK_RE = /第([0-9,~\-]+)周/;
const SECTION_RE = /[0-9]{2}(?:~[0-9]{2})+(?:小)?节/;
const DAY_RE = /星期([一二三四五六日天])/;
const WEEK_DAY = { 一: 1, 二: 2, 三: 3, 四: 4, 五: 5, 六: 6, 日: 7, 天: 7 };

/** “第1,3,5-8周” → "1,3,5-8"；出现“单/双周”时再筛一遍。 */
function weeksFromZcd(zcd, text) {
  const weeks = new Set();
  for (const part of String(zcd || '').split(',')) {
    const m = /(\d{1,2})\s*(?:[-~～]\s*(\d{1,2}))?/.exec(part);
    if (!m) continue;
    const a = Number(m[1]);
    const b = m[2] === undefined ? a : Number(m[2]);
    if (a >= 1 && a <= 30 && b >= a && b <= 30) for (let w = a; w <= b; w++) weeks.add(w);
  }
  if (!weeks.size) return '';
  let list = [...weeks].sort((x, y) => x - y);
  const src = text || '';
  const odd = /单周|[（(]\s*单\s*[）)]/.test(src);
  const even = /双周|[（(]\s*双\s*[）)]/.test(src);
  if (odd && !even) list = list.filter((w) => w % 2 === 1);
  if (even && !odd) list = list.filter((w) => w % 2 === 0);
  return weeksToText(list);
}

/** 去掉周次/节次/星期/教师之后，剩下的文字里带“楼室馆场校区号”的那段就是上课地点。 */
function roomOf(seg) {
  const rest = seg
    .replace(new RegExp(WEEK_RE.source, 'g'), ' ')
    .replace(new RegExp(SECTION_RE.source, 'g'), ' ')
    .replace(new RegExp(DAY_RE.source, 'g'), ' ');
  const tokens = rest
    .split(/\s+/)
    .filter((t) => t && !/^\d{1,2}$/.test(t) && !/^(?:教师|老师)[:：]/.test(t));
  return tokens.find((t) => /楼|室|馆|场|中心|校区|号/.test(t)) || tokens[0] || '';
}

/** 解析 jsxsd 主界面 AJAX 返回的那张课表。 */
function parseJsxsd(html) {
  const tables = [...html.matchAll(/<table[\s\S]*?<\/table>/gi)].map((m) => m[0]);
  const table =
    tables.find((t) => /item-box/i.test(t)) || tables.find((t) => /星期/.test(stripTags(t)));
  if (!table) return [];

  const out = [];
  const seen = new Set();
  const rows = [...table.matchAll(/<tr[\s\S]*?<\/tr>/gi)].map((m) => m[0]);
  // 第一行是表头（周/节次 + 星期一…星期日）
  rows.slice(1).forEach((row, i) => {
    const rowIndex = i + 1;
    const cells = [...row.matchAll(/<td[\s\S]*?<\/td>/gi)].map((m) => m[0]);
    if (cells.length < 2) return;
    const rowSection = sectionsOf(stripTags(cells[0]), rowIndex);

    cells.slice(1).forEach((cell, idx) => {
      const text = stripTags(cell);
      const day = WEEK_DAY[(DAY_RE.exec(text) || [])[1]] || idx + 1;
      if (!(day >= 1 && day <= 7)) return;
      const cellTeacher = (/教师[:：]\s*([^\s]+)/.exec(text) || [])[1] || '';

      for (const unit of courseUnits(cell)) {
        const teacher = (/教师[:：]\s*([^\s]+)/.exec(stripTags(unit)) || [])[1] || cellTeacher;
        const boxes = itemBoxes(unit);
        for (const { name, seg, meta } of boxCourses(boxes.length ? boxes[0] : unit)) {
          const weeks = weeksFromZcd((WEEK_RE.exec(meta) || [])[1] || '', meta);
          if (!weeks) continue;
          const { start, end } = SECTION_RE.test(meta) ? sectionsOf(meta, rowIndex) : rowSection;
          const room = roomOf(seg);
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
      }
    });
  });

  out.sort((a, b) => a.day - b.day || a.start - b.start || a.name.localeCompare(b.name, 'zh'));
  return out;
}

async function syncTimetable(session, user, termStart) {
  const { xnm, xqm } = termOf(termStart || '2026-08-31');
  const candidates = [
    { xnm, xqm },
    { xnm, xqm: xqm === '3' ? '12' : '3' },
    { xnm: String(Number(xnm) - 1), xqm: '12' },
    { xnm: String(Number(xnm) - 1), xqm: '3' }
  ];
  let expired = false;
  for (const t of candidates) {
    const got = await fetchKb(session, t.xnm, t.xqm, user);
    if (got.needLogin) {
      expired = true;
      break;
    }
    const courses = got.json ? parseCourses(got.json) : [];
    if (courses.length) return { courses, term: t, source: 'jwglxt' };
  }

  // jwglxt 接口拿不到就退回解析教务首页课表，南林这套实际是 jsxsd
  const page = await fetchJsxsd(session);
  if (page) {
    const courses = parseJsxsd(page);
    if (courses.length) return { courses, term: { xnm, xqm }, source: 'jsxsd' };
    if (DEBUG) console.log('[jsxsd] 页面拿到了但没解析出课程，见 %TEMP%\\ct-html');
  }

  if (expired) {
    const err = new Error('教务会话已失效，请重新登录');
    err.code = 'NEED_LOGIN';
    throw err;
  }
  throw new Error('教务没有返回课表数据（可能本学期参数不对，或账号没有课表）');
}

/* --------------------------------------------------------------- 服务端 */

/**
 * 会话 Cookie。部署到 HTTPS 域名（Render/Railway/Nginx）时必须带 Secure，
 * 本机 http 调试则不能带，否则浏览器会直接丢弃。
 */
function sidCookie(req, sid, maxAge) {
  const proto = String(req.headers['x-forwarded-proto'] || '').split(',')[0].trim();
  const secure = proto === 'https' || !!req.socket.encrypted;
  return `${SID_COOKIE}=${sid}; Path=/; HttpOnly; SameSite=Lax; Max-Age=${maxAge}${secure ? '; Secure' : ''}`;
}

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
  if (DEBUG) console.log(`[in] ${req.method} ${url.pathname}${url.search} cookie=${req.headers.cookie ? '有' : '无'}`);
  try {
    if (url.pathname === '/api/me') {
      const s = sessionOf(req);
      return json(res, 200, {
        loggedIn: !!(s && hasJwxtSession(s)),
        user: s ? s.user : ''
      });
    }

    if (url.pathname === '/api/logout') {
      const sid = cookieSid(req);
      if (sid) sessions.delete(sid);
      res.writeHead(200, {
        'Content-Type': 'application/json; charset=utf-8',
        'Set-Cookie': sidCookie(req, '', 0)
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
      // 先下发会话 Cookie：即使后面同步失败，也不用再重新登录，点“同步”重试即可
      res.setHeader('Set-Cookie', sidCookie(req, sid, 86400));
      const result = await syncTimetable(stored, username.trim(), termStart);
      stored.updatedAt = Date.now();
      return json(res, 200, { ok: true, user: username.trim(), ...result });
    }

    if (url.pathname === '/api/cookie' && req.method === 'POST') {
      // 兜底：自动登录被风控挡住时，用户可从浏览器复制 Cookie 粘进来
      const { cookie = '', user = '', termStart = '' } = await readBody(req);
      if (!cookie) return json(res, 400, { error: '请粘贴 Cookie' });
      let sid = cookieSid(req);
      if (!sid || !sessions.has(sid)) {
        sid = newSession();
        res.setHeader('Set-Cookie', sidCookie(req, sid, 86400));
      }
      const s = sessions.get(sid);
      for (const part of cookie.split(';')) {
        const idx = part.indexOf('=');
        if (idx <= 0) continue;
        // 教务 Cookie 统一挂在 jwxt 域下
        s.jar.set(`${JWXT_HOST}|${part.slice(0, idx).trim()}`, part.slice(idx + 1).trim());
      }
      s.user = user.trim();
      const result = await syncTimetable(s, user.trim(), termStart);
      return json(res, 200, { ok: true, ...result });
    }

    if (url.pathname === '/api/timetable' && req.method === 'POST') {
      const s = sessionOf(req);
      if (!s || !hasJwxtSession(s)) return json(res, 401, { error: '还没登录', code: 'NEED_LOGIN' });
      const { termStart = '' } = await readBody(req);
      const result = await syncTimetable(s, s.user, termStart);
      return json(res, 200, { ok: true, user: s.user, ...result });
    }

    // 调试用：用最近一个会话抓一次课表页，确认解析结果
    if (url.pathname === '/api/_debug' && DEBUG) {
      const s = sessionOf(req) || [...sessions.values()].pop();
      if (!s) return json(res, 400, { error: '没有会话' });
      const page = await fetchJsxsd(s);
      const courses = page ? parseJsxsd(page) : [];
      return json(res, 200, {
        ok: !!page,
        len: page ? page.length : 0,
        courses: courses.length,
        sample: courses.slice(0, 3)
      });
    }

    if (url.pathname.startsWith('/api/')) return json(res, 404, { error: '接口不存在' });

    serveStatic(req, res);
  } catch (err) {
    const code = (err && err.code) || 'ERROR';
    if (DEBUG) console.log(`[api] 失败 ${url.pathname} ${code} ${(err && err.message) || ''}`);
    const status = code === 'NEED_LOGIN' ? 401 : code === 'BAD_CREDENTIALS' ? 401 : code === 'NEED_CAPTCHA' ? 400 : 500;
    json(res, status, {
      error: (err && err.message) || '服务出错',
      code
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

if (require.main === module) {
  server.listen(PORT, HOST, () => {
    if (HOSTED) {
      console.log(`课表网页版已启动，端口 ${PORT}（云托管）`);
      console.log('提示：会话只存内存，平台休眠/重启后手机上重新登录一次即可。');
      return;
    }
    console.log(`课表网页版已启动： http://127.0.0.1:${PORT}`);
    if (HOST === '0.0.0.0') {
      for (const list of Object.values(os.networkInterfaces())) {
        for (const item of list || []) {
          if (item.family === 'IPv4' && !item.internal) {
            console.log(`  手机同网段可用： http://${item.address}:${PORT}`);
          }
        }
      }
      console.log('  注意：同 Wi‑Fi 下的其他设备也能打开，公共网络下用完记得关掉。');
    }
    console.log('提示：只在本机/局域网使用，Cookie 保存在内存里，重启即失效。');
  });
}

// 给测试脚本用：直接调用内部函数，不用真的起服务
module.exports = {
  encryptPassword,
  jsxsdParams,
  weeksFromZcd,
  roomOf,
  parseJsxsd,
  fetchJsxsd,
  casLogin,
  syncTimetable,
  hasJwxtSession,
  sessions,
  newSession,
  server
};
