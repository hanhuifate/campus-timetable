'use strict';

/* ------------------------------------------------------------------ 常量 */

const CAMPUS_TIMES = {
  白马: [
    '08:30-09:15', '09:20-10:05', '10:25-11:10', '11:15-12:00',
    '14:00-14:45', '14:50-15:35', '15:55-16:40', '16:45-17:30',
    '18:30-19:15', '19:20-20:05', '20:10-20:55', '21:00-21:45'
  ],
  新庄: [
    '08:00-08:45', '08:55-09:40', '10:00-10:45', '10:55-11:40',
    '14:00-14:45', '14:50-15:35', '15:55-16:40', '16:45-17:30',
    '18:30-19:15', '19:20-20:05', '20:10-20:55', '21:00-21:45'
  ],
  淮安: [
    '08:00-08:45', '08:55-09:40', '10:00-10:45', '10:55-11:40',
    '14:00-14:45', '14:50-15:35', '15:55-16:40', '16:45-17:30',
    '18:30-19:15', '19:20-20:05', '20:10-20:55', '21:00-21:45'
  ]
};

const COLORS = ['#4c6ef5', '#f76707', '#12b886', '#e64980', '#7048e8', '#1098ad', '#f59f00', '#d6336c'];
const DAY_NAMES = ['周一', '周二', '周三', '周四', '周五', '周六', '周日'];
const SECTIONS = 12;

const KEY = { courses: 'ct.courses', settings: 'ct.settings', meta: 'ct.meta' };

/* --------------------------------------------------------------- 小工具 */

const $ = (sel) => document.querySelector(sel);

function el(tag, cls, text) {
  const node = document.createElement(tag);
  if (cls) node.className = cls;
  if (text !== undefined) node.textContent = text;
  return node;
}

function load(key, fallback) {
  try {
    const raw = localStorage.getItem(key);
    return raw ? JSON.parse(raw) : fallback;
  } catch {
    return fallback;
  }
}

function save(key, value) {
  try {
    localStorage.setItem(key, JSON.stringify(value));
  } catch {
    /* 隐私模式下忽略 */
  }
}

function parseDay(text) {
  const [y, m, d] = String(text).split('-').map(Number);
  return new Date(y, (m || 1) - 1, d || 1);
}

function addDays(date, n) {
  const d = new Date(date);
  d.setDate(d.getDate() + n);
  return d;
}

function pad(n) {
  return String(n).padStart(2, '0');
}

function ymd(date) {
  return `${date.getFullYear()}${pad(date.getMonth() + 1)}${pad(date.getDate())}`;
}

function mdText(date) {
  return `${date.getMonth() + 1}月${date.getDate()}日`;
}

function defaultTermStart() {
  const now = new Date();
  const year = now.getMonth() >= 7 ? now.getFullYear() : now.getFullYear() - 1;
  let d = new Date(year, 8, 1);
  const shift = (8 - d.getDay()) % 7;
  d = addDays(d, shift);
  return `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())}`;
}

/* ----------------------------------------------------------------- 状态 */

let courses = load(KEY.courses, []);
let settings = Object.assign(
  { campus: '', termStart: defaultTermStart(), leadMinutes: 25, showWeekend: false },
  load(KEY.settings, {})
);
let meta = Object.assign({ user: '', updatedAt: 0 }, load(KEY.meta, {}));

let viewWeek = 0;
let serverLoggedIn = false;
let detailCourse = null;

if (!settings.campus) settings.campus = guessCampus(courses);

function guessCampus(list) {
  const bai = list.filter((c) => String(c.room || '').includes('白马')).length;
  return bai > list.length - bai ? '白马' : '新庄';
}

function saveCourses() {
  save(KEY.courses, courses);
  save(KEY.meta, meta);
}

function saveSettings() {
  save(KEY.settings, settings);
}

/* --------------------------------------------------------------- 周计算 */

function termMonday() {
  const d = parseDay(settings.termStart);
  const offset = (d.getDay() + 6) % 7;
  return addDays(d, -offset);
}

function currentWeek() {
  const monday = termMonday();
  const today = new Date();
  today.setHours(0, 0, 0, 0);
  const diff = Math.floor((today - monday) / 86400000);
  return Math.max(1, Math.min(30, Math.floor(diff / 7) + 1));
}

function weekMonday(week) {
  return addDays(termMonday(), (week - 1) * 7);
}

function parseWeeks(text) {
  const set = new Set();
  String(text || '').split(',').forEach((part) => {
    const t = part.trim();
    if (!t) return;
    const m = /^(\d{1,2})\s*-\s*(\d{1,2})$/.exec(t);
    if (m) {
      for (let i = Number(m[1]); i <= Number(m[2]); i++) set.add(i);
    } else if (/^\d{1,2}$/.test(t)) {
      set.add(Number(t));
    }
  });
  return set;
}

function inWeek(course, week, cache) {
  if (!cache[course.id]) cache[course.id] = parseWeeks(course.weeks);
  return cache[course.id].has(week);
}

/* --------------------------------------------------------------- 渲染 */

function renderWeekNav() {
  const monday = weekMonday(viewWeek);
  const sunday = addDays(monday, 6);
  $('#weekTitle').textContent = `第 ${viewWeek} 周`;
  $('#weekRange').textContent =
    `${monday.getFullYear()}年${mdText(monday)} - ${mdText(sunday)}` +
    (viewWeek === currentWeek() ? ' · 本周' : '');
}

function renderSubtitle() {
  const parts = [];
  if (meta.user) parts.push(meta.user);
  parts.push(`第 ${currentWeek()} 周`);
  if (courses.length) {
    parts.push(`${courses.length} 节课`);
  } else {
    parts.push('还没有课表');
  }
  $('#subtitle').textContent = parts.join(' · ');
}

function sectionRange(campus, start, end, index) {
  const table = CAMPUS_TIMES[campus] || CAMPUS_TIMES.新庄;
  const a = (table[start - 1] || '').split('-')[0];
  const b = (table[end - 1] || '').split('-')[1];
  return { start: a, end: b, label: `${start}-${end} 节 · ${a}-${b}` };
}

function renderToday() {
  const card = $('#todayCard');
  card.hidden = false;
  card.innerHTML = '';

  const nowWeek = currentWeek();
  if (viewWeek !== nowWeek) {
    card.hidden = true;
    return;
  }

  const today = new Date();
  const day = (today.getDay() + 6) % 7 + 1;
  const todayKey = ymd(today);
  const now = today.getHours() * 60 + today.getMinutes();

  const list = courses
    .filter((c) => Number(c.day) === day && inWeek(c, nowWeek, {}))
    .sort((a, b) => a.start - b.start);

  const head = el('div', 'today-head');
  head.append(el('h2', null, `今天 · ${DAY_NAMES[day - 1]}`));
  head.append(el('span', null, `${today.getMonth() + 1}月${today.getDate()}日`));
  card.append(head);

  if (!list.length) {
    card.append(el('div', 'today-empty', '今天没有课，休息一下'));
    return;
  }

  const wrap = el('div', 'today-list');
  for (const c of list) {
    const range = sectionRange(settings.campus, c.start, c.end);
    const startMin = toMin(range.start);
    const endMin = toMin(range.end);
    const row = el('button', 'today-item');
    row.type = 'button';
    row.dataset.id = c.id;
    if (endMin !== null && now > endMin) row.classList.add('over');
    else if (startMin !== null && endMin !== null && now >= startMin) row.classList.add('now');

    const time = el('div', 'ti-time', `${range.start}\n${range.end}`);
    time.style.whiteSpace = 'pre-line';

    const main = el('div', 'ti-main');
    main.append(el('div', 'ti-name', c.name));
    main.append(el('div', 'ti-meta', [c.room, c.teacher].filter(Boolean).join(' · ')));

    const badge = el('div', 'ti-badge');
    if (row.classList.contains('now')) badge.textContent = '进行中';
    else if (row.classList.contains('over')) badge.textContent = '已结束';
    else if (startMin !== null) badge.textContent = `${Math.max(0, startMin - now)} 分钟后`;
    else badge.textContent = '';

    row.append(time, main, badge);
    row.addEventListener('click', () => showDetail(c));
    wrap.append(row);
  }
  card.append(wrap);
}

function toMin(timeText) {
  if (!timeText) return null;
  const [h, m] = timeText.split(':').map(Number);
  return h * 60 + m;
}

function renderGrid() {
  const grid = $('#grid');
  const showWeekend = !!settings.showWeekend;
  const days = showWeekend ? [1, 2, 3, 4, 5, 6, 7] : [1, 2, 3, 4, 5];
  const rowH = parseFloat(getComputedStyle(document.documentElement).getPropertyValue('--row-h')) || 62;

  grid.classList.toggle('no-weekend', !showWeekend);
  grid.innerHTML = '';

  const monday = weekMonday(viewWeek);
  const today = new Date();
  const isThisWeek = viewWeek === currentWeek();
  const todayDay = (today.getDay() + 6) % 7 + 1;

  grid.append(el('div', 'dhead'));

  for (const day of days) {
    const head = el('div', 'dhead');
    const date = addDays(monday, day - 1);
    head.append(el('span', null, DAY_NAMES[day - 1]));
    head.append(el('small', null, `${date.getMonth() + 1}/${date.getDate()}`));
    if (isThisWeek && day === todayDay) head.classList.add('today');
    grid.append(head);
  }

  const timecol = el('div', 'timecol');
  timecol.style.height = `${SECTIONS * rowH}px`;
  const table = CAMPUS_TIMES[settings.campus] || CAMPUS_TIMES.新庄;
  for (let i = 0; i < SECTIONS; i++) {
    const cell = el('div', 'timecell');
    cell.style.top = `${i * rowH}px`;
    cell.style.height = `${rowH}px`;
    cell.append(el('b', null, String(i + 1)));
    cell.append(el('span', null, (table[i] || '').split('-')[0] || ''));
    timecol.append(cell);
  }
  grid.append(timecol);

  const weekCache = {};
  for (const day of days) {
    const col = el('div', 'daycol');
    col.style.height = `${SECTIONS * rowH}px`;
    if (isThisWeek && day === todayDay) col.classList.add('today');

    const list = courses
      .filter((c) => Number(c.day) === day && inWeek(c, viewWeek, weekCache))
      .sort((a, b) => a.start - b.start);

    for (const c of list) {
      const rows = Math.max(1, c.end - c.start + 1);
      const height = rows * rowH - 5;
      const block = el('button', 'course');
      block.type = 'button';
      block.style.top = `${(c.start - 1) * rowH + 2}px`;
      block.style.height = `${height}px`;
      block.style.setProperty('--c', COLORS[Math.abs(c.color || 0) % COLORS.length]);
      if (height >= rowH * 2) block.classList.add('tall');
      if (height < 40) block.classList.add('compact');

      const name = el('div', 'c-name', c.name);
      block.append(name);
      if (height >= 40) {
        block.append(el('div', 'c-meta', c.room || ''));
      }
      block.addEventListener('click', () => showDetail(c));
      col.append(block);
    }
    grid.append(col);
  }
}

function render() {
  renderWeekNav();
  renderSubtitle();
  renderToday();
  renderGrid();
  $('#emptyHint').hidden = courses.length > 0;
}

/* --------------------------------------------------------------- 详情 */

function showDetail(course) {
  detailCourse = course;
  const range = sectionRange(settings.campus, course.start, course.end);
  $('#detailTitle').textContent = course.name;
  $('#detailSub').textContent = `周${'一二三四五六日'[course.day - 1]} · ${range.label}`;
  $('#detailTime').textContent = `${range.start} - ${range.end}`;
  $('#detailRoom').textContent = course.room || '未写教室';
  $('#detailTeacher').textContent = course.teacher || '未写教师';
  $('#detailWeeks').textContent = course.weeks ? `${course.weeks} 周` : '未写周次';
  $('#detailMask').hidden = false;
}

/* --------------------------------------------------------------- 弹层 */

function openMask(id) {
  $(id).hidden = false;
}

function closeMask(id) {
  $(id).hidden = true;
}

let toastTimer = 0;
function toast(text) {
  const node = $('#toast');
  node.textContent = text;
  node.hidden = false;
  clearTimeout(toastTimer);
  toastTimer = setTimeout(() => { node.hidden = true; }, 2600);
}

/* ------------------------------------------------------------- 接口调用 */

async function api(path, body) {
  const res = await fetch(path, {
    method: body ? 'POST' : 'GET',
    headers: body ? { 'Content-Type': 'application/json' } : undefined,
    body: body ? JSON.stringify(body) : undefined
  });
  let data = {};
  try {
    data = await res.json();
  } catch {
    throw new Error(`服务返回异常（HTTP ${res.status}）`);
  }
  if (!res.ok) {
    const err = new Error(data.error || `请求失败（HTTP ${res.status}）`);
    err.code = data.code || '';
    err.status = res.status;
    throw err;
  }
  return data;
}

function applySync(data) {
  if (Array.isArray(data.courses)) courses = data.courses;
  if (data.user) meta.user = data.user;
  meta.updatedAt = Date.now();
  if (!settings.campus) {
    settings.campus = guessCampus(courses);
    saveSettings();
  }
  saveCourses();
  viewWeek = currentWeek();
  render();
}

async function checkSession() {
  try {
    const me = await api('/api/me');
    serverLoggedIn = !!me.loggedIn;
    if (me.user && !meta.user) {
      meta.user = me.user;
      save(KEY.meta, meta);
      renderSubtitle();
    }
    const stale = Date.now() - (meta.updatedAt || 0) > 6 * 3600 * 1000;
    if (serverLoggedIn && (!courses.length || stale)) {
      await api('/api/timetable', { termStart: settings.termStart })
        .then(applySync)
        .catch((err) => {
          if (err.code === 'NEED_LOGIN') serverLoggedIn = false;
        });
    }
  } catch {
    /* 没开服务或网络不通：只用本地缓存 */
  }
}

/* --------------------------------------------------------------- 登录 */

function switchTab(name) {
  document.querySelectorAll('.tab').forEach((t) => t.classList.toggle('active', t.dataset.tab === name));
  $('#accountForm').hidden = name !== 'account';
  $('#cookieForm').hidden = name !== 'cookie';
}

function openLogin() {
  $('#loginError').hidden = true;
  $('#cookieError').hidden = true;
  switchTab('account');
  if (meta.user) $('#inUser').value = meta.user;
  openMask('#loginMask');
  setTimeout(() => $('#inUser').focus(), 250);
}

async function submitLogin(event) {
  event.preventDefault();
  const btn = event.target.querySelector('button[type=submit]');
  const username = $('#inUser').value.trim();
  const password = $('#inPass').value;
  if (!username || !password) return;
  const error = $('#loginError');
  error.hidden = true;
  btn.disabled = true;
  btn.textContent = '登录中…';
  try {
    const data = await api('/api/login', {
      username,
      password,
      termStart: settings.termStart,
      user: username
    });
    serverLoggedIn = true;
    applySync(data);
    $('#inPass').value = '';
    closeMask('#loginMask');
    toast(`已同步 ${courses.length} 节课`);
  } catch (err) {
    error.textContent = err.message;
    error.hidden = false;
    if (err.code === 'NEED_CAPTCHA') {
      switchTab('cookie');
      toast('自动登录被验证码拦住，已切到 Cookie 方式');
    }
  } finally {
    btn.disabled = false;
    btn.textContent = '登录并同步';
  }
}

async function submitCookie(event) {
  event.preventDefault();
  const btn = event.target.querySelector('button[type=submit]');
  const cookie = $('#inCookie').value.trim();
  const user = $('#inCookieUser').value.trim() || meta.user;
  if (!cookie) return;
  const error = $('#cookieError');
  error.hidden = true;
  btn.disabled = true;
  btn.textContent = '同步中…';
  try {
    const data = await api('/api/cookie', {
      cookie,
      user,
      termStart: settings.termStart
    });
    serverLoggedIn = true;
    applySync(data);
    $('#inCookie').value = '';
    closeMask('#loginMask');
    toast(`已同步 ${courses.length} 节课`);
  } catch (err) {
    error.textContent = err.message;
    error.hidden = false;
  } finally {
    btn.disabled = false;
    btn.textContent = '用 Cookie 同步';
  }
}

/* --------------------------------------------------------------- 同步 */

async function onSync() {
  if (!serverLoggedIn) {
    openLogin();
    return;
  }
  const btn = $('#btnSync');
  btn.disabled = true;
  try {
    const data = await api('/api/timetable', { termStart: settings.termStart });
    applySync(data);
    toast(`已同步 ${courses.length} 节课`);
  } catch (err) {
    if (err.code === 'NEED_LOGIN') {
      serverLoggedIn = false;
      openLogin();
      toast('登录已过期，请重新登录');
    } else {
      toast(err.message);
    }
  } finally {
    btn.disabled = false;
  }
}

/* --------------------------------------------------------------- 设置 */

function openSettings() {
  $('#selCampus').value = settings.campus;
  $('#inTermStart').value = settings.termStart;
  $('#inLead').value = String(settings.leadMinutes);
  $('#chkWeekend').checked = !!settings.showWeekend;
  $('#cacheNote').textContent = meta.updatedAt
    ? `本地缓存：${new Date(meta.updatedAt).toLocaleString('zh-CN')}`
    : '还没有同步过课表';
  openMask('#settingsMask');
}

function readSettings() {
  settings.campus = $('#selCampus').value;
  const term = $('#inTermStart').value;
  if (term) settings.termStart = term;
  const lead = Number($('#inLead').value);
  settings.leadMinutes = Number.isFinite(lead) ? Math.max(0, Math.min(120, lead)) : 25;
  settings.showWeekend = $('#chkWeekend').checked;
  saveSettings();
  viewWeek = Math.min(Math.max(viewWeek, 1), 30);
  render();
}

/* ------------------------------------------------------------ 导出 .ics */

function icsEscape(text) {
  return String(text || '')
    .replace(/\\/g, '\\\\')
    .replace(/;/g, '\\;')
    .replace(/,/g, '\\,')
    .replace(/\r?\n/g, '\\n');
}

function foldLine(line) {
  const encoder = new TextEncoder();
  const parts = [];
  let buf = '';
  let bytes = 0;
  for (const ch of line) {
    const size = encoder.encode(ch).length;
    if (bytes + size > 73) {
      parts.push(buf);
      buf = '';
      bytes = 0;
    }
    buf += ch;
    bytes += size;
  }
  parts.push(buf);
  let out = parts[0];
  for (let i = 1; i < parts.length; i++) out += `\r\n ${parts[i]}`;
  return out;
}

function icsDateTime(date, timeText) {
  const [h, m] = timeText.split(':').map(Number);
  const d = new Date(date);
  d.setHours(h || 0, m || 0, 0, 0);
  return `${ymd(d)}T${pad(d.getHours())}${pad(d.getMinutes())}00`;
}

function exportIcs() {
  if (!courses.length) {
    toast('还没有课表，先同步一次');
    return;
  }

  const lead = Math.max(0, Number(settings.leadMinutes) || 0);
  const lines = [
    'BEGIN:VCALENDAR',
    'VERSION:2.0',
    'PRODID:-//NJFU Timetable//CN',
    'CALSCALE:GREGORIAN',
    'METHOD:PUBLISH',
    'X-WR-CALNAME:南林课表',
    'X-WR-TIMEZONE:Asia/Shanghai',
    'BEGIN:VTIMEZONE',
    'TZID:Asia/Shanghai',
    'X-LIC-LOCATION:Asia/Shanghai',
    'BEGIN:STANDARD',
    'DTSTART:19700101T000000',
    'TZOFFSETFROM:+0800',
    'TZOFFSETTO:+0800',
    'TZNAME:CST',
    'END:STANDARD',
    'END:VTIMEZONE'
  ];

  const stamp = new Date().toISOString().replace(/[-:]/g, '').replace(/\.\d{3}/, '');

  for (const c of courses) {
    const weeks = [...parseWeeks(c.weeks)].sort((a, b) => a - b);
    if (!weeks.length) continue;
    const monday = termMonday();
    const dayDate = addDays(monday, (c.day - 1));
    const range = sectionRange(settings.campus, c.start, c.end);
    if (!range.start || !range.end) continue;

    for (const week of weeks) {
      const date = addDays(dayDate, (week - 1) * 7);
      const start = icsDateTime(date, range.start);
      const end = icsDateTime(date, range.end);
      lines.push(
        'BEGIN:VEVENT',
        `UID:${c.id}-w${week}@njfu-timetable`,
        `DTSTAMP:${stamp}`,
        `DTSTART;TZID=Asia/Shanghai:${start}`,
        `DTEND;TZID=Asia/Shanghai:${end}`,
        foldLine(`SUMMARY:${icsEscape(c.name)}`),
        foldLine(`LOCATION:${icsEscape(c.room || '')}`),
        foldLine(`DESCRIPTION:${icsEscape([c.teacher, c.room].filter(Boolean).join(' · '))}`)
      );
      if (lead > 0) {
        lines.push(
          'BEGIN:VALARM',
          `TRIGGER:-PT${lead}M`,
          'ACTION:DISPLAY',
          foldLine(`DESCRIPTION:${icsEscape(c.name)}`),
          'END:VALARM'
        );
      }
      lines.push('END:VEVENT');
    }
  }

  lines.push('END:VCALENDAR');
  const blob = new Blob([lines.join('\r\n') + '\r\n'], { type: 'text/calendar;charset=utf-8' });
  const url = URL.createObjectURL(blob);
  const link = document.createElement('a');
  link.href = url;
  link.download = '南林课表.ics';
  document.body.append(link);
  link.click();
  link.remove();
  setTimeout(() => URL.revokeObjectURL(url), 3000);
  toast('已导出，用日历 App 打开即可');
}

/* --------------------------------------------------------------- 事件 */

function bind() {
  $('#btnPrev').addEventListener('click', () => {
    viewWeek = Math.max(1, viewWeek - 1);
    render();
  });
  $('#btnNext').addEventListener('click', () => {
    viewWeek = Math.min(30, viewWeek + 1);
    render();
  });
  $('#btnToday').addEventListener('click', () => {
    viewWeek = currentWeek();
    render();
  });
  $('#btnSync').addEventListener('click', onSync);
  $('#btnSettings').addEventListener('click', openSettings);

  $('#accountForm').addEventListener('submit', submitLogin);
  $('#cookieForm').addEventListener('submit', submitCookie);

  document.querySelectorAll('.tab').forEach((tab) => {
    tab.addEventListener('click', () => switchTab(tab.dataset.tab));
  });

  document.addEventListener('click', (event) => {
    const target = event.target;
    if (target.closest('[data-close]')) closeMask('#detailMask');
    if (target.closest('[data-close-login]')) closeMask('#loginMask');
    if (target.closest('[data-close-settings]')) closeMask('#settingsMask');
  });

  ['#detailMask', '#loginMask', '#settingsMask'].forEach((id) => {
    $(id).addEventListener('click', (event) => {
      if (event.target === event.currentTarget) event.currentTarget.hidden = true;
    });
  });

  document.addEventListener('keydown', (event) => {
    if (event.key === 'Escape') {
      closeMask('#detailMask');
      closeMask('#loginMask');
      closeMask('#settingsMask');
    }
  });

  $('#selCampus').addEventListener('change', readSettings);
  $('#inTermStart').addEventListener('change', readSettings);
  $('#inLead').addEventListener('change', readSettings);
  $('#chkWeekend').addEventListener('change', readSettings);
  $('#btnExport').addEventListener('click', exportIcs);

  $('#btnLogout').addEventListener('click', async () => {
    try {
      await api('/api/logout');
    } catch {
      /* 服务没开也无所谓，本地清掉即可 */
    }
    serverLoggedIn = false;
    meta.user = '';
    meta.updatedAt = 0;
    save(KEY.meta, meta);
    closeMask('#settingsMask');
    toast('已退出登录，本地课表保留');
    render();
  });

  setInterval(() => {
    renderToday();
    renderSubtitle();
  }, 30 * 1000);
}

function registerServiceWorker() {
  if (!('serviceWorker' in navigator)) return;
  navigator.serviceWorker.register('/sw.js').catch(() => {});
}

/* ----------------------------------------------------------------- 启动 */

viewWeek = currentWeek();
bind();
render();
checkSession();
registerServiceWorker();
