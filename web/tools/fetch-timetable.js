/**
 * 把课表导出成静态 JSON，提交到仓库后手机端打开网页直接拉取。
 *
 * 用法（在 web 目录下）：
 *   PowerShell: $env:CT_USERNAME="学号"; $env:CT_PASSWORD="密码"; node tools/fetch-timetable.js
 *   也可以直接传参：node tools/fetch-timetable.js 学号 密码
 *
 * 输出：public/data/timetable.json（只含课程列表，不写学号、姓名）
 */
'use strict';

const fs = require('node:fs');
const path = require('node:path');
const dns = require('node:dns');
// 云主机（GitHub Actions 等）通常没有 IPv6 出口，优先用 IPv4 免得连超时
dns.setDefaultResultOrder('ipv4first');
const { casLogin, syncTimetable } = require('../server.js');

const USER = process.env.CT_USERNAME || process.argv[2];
const PASS = process.env.CT_PASSWORD || process.argv[3];

(async () => {
  if (!USER || !PASS) {
    console.error('缺少账号密码。示例：$env:CT_USERNAME="学号"; $env:CT_PASSWORD="密码"; node tools/fetch-timetable.js');
    process.exit(1);
  }

  console.log(`登录 ${USER} …`);
  const fresh = await casLogin(USER, PASS);
  const session = { jar: fresh.jar, user: USER, updatedAt: Date.now() };

  console.log('抓取课表 …');
  const result = await syncTimetable(session, USER, process.env.CT_TERM_START || '');
  const courses = result.courses || [];
  if (!courses.length) throw new Error('没抓到课程，可能是学期参数不对，或本学期确实没有课');

  const dir = path.join(__dirname, '..', 'public', 'data');
  fs.mkdirSync(dir, { recursive: true });
  const out = path.join(dir, 'timetable.json');
  fs.writeFileSync(
    out,
    JSON.stringify({ syncedAt: new Date().toISOString(), count: courses.length, courses }, null, 2) + '\n',
    'utf8'
  );

  console.log(`已写入 ${path.relative(process.cwd(), out)}：${courses.length} 门课`);
  console.log('提交并推送后，手机上打开网页点「刷新」就能看到。');
})().catch((err) => {
  const cause = err && err.cause ? ` (${err.cause.code || err.cause.message})` : '';
  console.error('导出失败：', ((err && err.message) || err) + cause);
  process.exit(1);
});
