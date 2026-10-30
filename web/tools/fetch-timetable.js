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

/* 云主机（GitHub Actions 等）没有 IPv6 出口，而学校域名在海外 DNS 里可能返回 AAAA，
   连接会一直卡到超时。这里把本进程的 DNS 查询钉死成 IPv4。 */
const lookupV4 = dns.lookup.bind(dns);
const promiseLookupV4 = dns.promises.lookup.bind(dns.promises);
const v4Options = (options) => {
  if (typeof options === 'number') return Object.assign({}, { family: 4 });
  return Object.assign({}, options || {}, { family: 4 });
};
dns.lookup = (host, options, callback) => {
  if (typeof options === 'function') return lookupV4(host, { family: 4 }, options);
  return lookupV4(host, v4Options(options), callback);
};
dns.promises.lookup = (host, options) => promiseLookupV4(host, v4Options(options));

const { casLogin, syncTimetable } = require('../server.js');

const USER = process.env.CT_USERNAME || process.argv[2];
const PASS = process.env.CT_PASSWORD || process.argv[3];
const sleep = (ms) => new Promise((resolve) => setTimeout(resolve, ms));

async function fetchOnce() {
  console.log(`登录 ${USER} …`);
  const fresh = await casLogin(USER, PASS);
  const session = { jar: fresh.jar, user: USER, updatedAt: Date.now() };

  console.log('抓取课表 …');
  const result = await syncTimetable(session, USER, process.env.CT_TERM_START || '');
  return result.courses || [];
}

(async () => {
  if (!USER || !PASS) {
    console.error('缺少账号密码。示例：$env:CT_USERNAME="学号"; $env:CT_PASSWORD="密码"; node tools/fetch-timetable.js');
    process.exit(1);
  }

  // 海外运行器访问学校站点常被重置，整条链路失败就整体重来
  let courses = [];
  let lastErr = null;
  for (let attempt = 1; attempt <= 3; attempt++) {
    try {
      courses = await fetchOnce();
      if (courses.length) break;
      lastErr = new Error('没抓到课程，可能是学期参数不对，或本学期确实没有课');
    } catch (err) {
      lastErr = err;
      const cause = err && err.cause ? ` (${err.cause.code || err.cause.message})` : '';
      console.error(`第 ${attempt} 次失败：`, ((err && err.message) || err) + cause);
      if (attempt < 3) await sleep(5000);
    }
  }
  if (!courses.length) throw lastErr || new Error('没抓到课程');

  const dir = path.join(__dirname, '..', 'public', 'data');
  fs.mkdirSync(dir, { recursive: true });
  const out = path.join(dir, 'timetable.json');

  // 课程没变就不写文件，免得定时任务每天都产生一个只有时间戳变化的空提交
  let previous = null;
  try {
    previous = JSON.parse(fs.readFileSync(out, 'utf8'));
  } catch (_) {
    previous = null;
  }
  if (previous && JSON.stringify(previous.courses) === JSON.stringify(courses)) {
    console.log(`课表没有变化：${courses.length} 门课`);
    return;
  }

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
