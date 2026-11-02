#!/usr/bin/env node
/**
 * ARCH 浏览器实测：用原生 CDP 逐路由渲染 apps/web，采集运行时证据。
 *
 * 两种模式：
 *   --sweep  按 roles × routes 真实渲染，落 JSON 报告（console/异常/HTTP>=400/最终 URL/
 *            innerText 指纹/#app 结构签名），并可选 --interaction 逐条执行交互探针。
 *   --diff   比较两份报告，按路由给出 SAME / TEXT_DIFF / STRUCT_DIFF / STATUS_DIFF 结论。
 *
 * 文本与结构分别指纹化：文本改一个字符即 DIFF，结构签名只记 tag.class 序列，
 * 用来区分「文案变了」和「DOM 形状变了」两类不同性质的回归。
 * 时间戳类易变文本统一掩码，掩码处数在报告里如实计数。
 */

import { spawn } from "node:child_process";
import { mkdtempSync, readFileSync, writeFileSync } from "node:fs";
import { tmpdir } from "node:os";
import { parseArgs } from "node:util";

const CHROME = "/Applications/Google Chrome.app/Contents/MacOS/Google Chrome";
const { values: options } = parseArgs({
  options: {
    mode: { type: "string" },
    base: { type: "string", default: "http://127.0.0.1:5173" },
    out: { type: "string" },
    left: { type: "string" },
    right: { type: "string" },
    password: { type: "string" },
    routes: { type: "string" },
    roles: { type: "string", default: "admin,warehouse,employee" },
    port: { type: "string", default: "9411" },
    label: { type: "string", default: "run" },
    settle: { type: "string", default: "450" },
  },
});

const sleep = (ms) => new Promise((resolve) => setTimeout(resolve, ms));

class Connection {
  constructor(socket) {
    this.socket = socket;
    this.next = 1;
    this.pending = new Map();
    this.listeners = [];
    this.socket.addEventListener("message", (event) => {
      const message = JSON.parse(event.data);
      if (message.id && this.pending.has(message.id)) {
        const { resolve, reject } = this.pending.get(message.id);
        this.pending.delete(message.id);
        if (message.error) reject(new Error(`${message.error.message}`));
        else resolve(message.result);
        return;
      }
      for (const listener of this.listeners) listener(message);
    });
  }

  send(method, params = {}, sessionId) {
    const id = this.next++;
    this.socket.send(JSON.stringify({ id, method, params, ...(sessionId ? { sessionId } : {}) }));
    return new Promise((resolve, reject) => this.pending.set(id, { resolve, reject }));
  }

  on(listener) {
    this.listeners.push(listener);
  }
}

async function connect(url) {
  const socket = new WebSocket(url);
  await new Promise((resolve, reject) => {
    socket.addEventListener("open", resolve, { once: true });
    socket.addEventListener("error", reject, { once: true });
  });
  return new Connection(socket);
}

async function waitForBrowser(port) {
  for (let attempt = 0; attempt < 80; attempt += 1) {
    try {
      const response = await fetch(`http://127.0.0.1:${port}/json/version`);
      if (response.ok) return (await response.json()).webSocketDebuggerUrl;
    } catch {
      /* Chrome 还没写出 DevToolsActivePort */
    }
    await sleep(250);
  }
  throw new Error("Chrome devtools endpoint never came up");
}

/** 易变文本掩码：日期、时间、时长倒计时。掩码处数计入报告，不静默吞掉差异。 */
function maskVolatile(text) {
  let masked = 0;
  const bump = () => { masked += 1; return "«T»"; };
  const patterns = [
    /\d{4}-\d{2}-\d{2}[ T]\d{2}:\d{2}(:\d{2})?/g,
    /\d{1,2}\/\d{1,2}\/\d{2,4},?\s+\d{1,2}:\d{2}/g,
    /\d{4}-\d{2}-\d{2}/g,
    /\d{2}:\d{2}:\d{2}/g,
  ];
  let output = text;
  for (const pattern of patterns) output = output.replace(pattern, bump);
  return { text: output, masked };
}

function normalize(text) {
  return text.replace(/\s+/g, " ").trim();
}

async function newPage(browser, startUrl) {
  const { targetId } = await browser.send("Target.createTarget", { url: startUrl });
  const session = await browser.send("Target.attachToTarget", { targetId, flatten: true });
  return { targetId, sessionId: session.sessionId };
}

async function evaluate(connection, sessionId, expression, awaitPromise = false) {
  const result = await connection.send("Runtime.evaluate",
    { expression, returnByValue: true, awaitPromise }, sessionId);
  if (result.exceptionDetails) {
    throw new Error(result.exceptionDetails.exception?.description || "evaluate threw");
  }
  return result.result.value;
}

/** 结构签名：深度优先的 tag.class 序列，不含文本与属性，用来定位 DOM 形状变化。 */
const STRUCTURE_EXPRESSION = `(() => {
  const root = document.querySelector("#app") || document.body;
  const walk = (node, depth) => {
    let out = [];
    for (const child of node.children) {
      const classes = [...child.classList].sort().join(".");
      out.push("  ".repeat(depth) + child.tagName.toLowerCase() + (classes ? "." + classes : ""));
      out = out.concat(walk(child, depth + 1));
    }
    return out;
  };
  return walk(root, 0).join("\\n");
})()`;

/** 交互清单：按钮/链接/输入的数量、禁用数与首个可点元素，用于证明事件接线未断。 */
const INVENTORY_EXPRESSION = `JSON.stringify((() => {
  const nodes = [...document.querySelectorAll("button, a, input, select, textarea")];
  const text = (element) => (element.innerText || element.value || element.getAttribute("aria-label") || element.name || "").trim().slice(0, 24);
  return {
    total: nodes.length,
    disabled: nodes.filter((element) => element.disabled || element.getAttribute("aria-disabled") === "true").length,
    buttons: nodes.filter((element) => element.tagName === "BUTTON").map(text).filter(Boolean).slice(0, 40),
    fields: nodes.filter((element) => /^(INPUT|SELECT|TEXTAREA)$/.test(element.tagName)).map((element) => element.type + ":" + (element.getAttribute("placeholder") || element.name || "")).slice(0, 40),
  };
})())`;

/**
 * 以真实登录换取会话，再把它交给浏览器：
 * 后端把 FAMS_SESSION 标了 Secure，http 回环下 Chrome 拒收该 Cookie，登录表单点下去也不会留住会话；
 * 这里照浏览器会做的那样写入同名非 Secure Cookie 与 sessionStorage 里的 CSRF 令牌，
 * 只替代「输入账号密码」这一步，后续每个路由仍是真实渲染与真实接口调用。
 */
async function adoptSession(connection, sessionId, base, username, password) {
  await connection.send("Network.clearBrowserCookies", {}, sessionId);
  const response = await fetch(`${base}/api/v1/auth/login`, {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify({ username, password }),
  });
  if (!response.ok) throw new Error(`login ${username} -> ${response.status} ${await response.text()}`);
  const session = await response.json();
  const raw = response.headers.getSetCookie().find((cookie) => cookie.startsWith("FAMS_SESSION="));
  if (!raw) throw new Error(`login ${username} issued no FAMS_SESSION cookie`);
  await connection.send("Network.setCookie", {
    name: "FAMS_SESSION",
    value: raw.split(";")[0].split("=").slice(1).join("="),
    path: "/api",
    httpOnly: true,
    secure: false,
    sameSite: "Lax",
    url: base,
  }, sessionId);
  // CSRF 令牌存在 sessionStorage，应用启动时读取；必须在首个脚本之前写入，
  // 否则 restore() 会先看到空令牌并把会话判定为需要重新登录。
  await connection.send("Page.addScriptToEvaluateOnNewDocument", {
    source: `sessionStorage.setItem("fams.csrf", ${JSON.stringify(session.csrfToken)});`,
  }, sessionId);
  await connection.send("Page.navigate", { url: `${base}/` }, sessionId);
  const probe = await evaluate(connection, sessionId,
    'fetch("/api/v1/auth/me", { credentials: "include" }).then((r) => r.status)', true);
  if (probe !== 200) throw new Error(`browser session for ${username} got /auth/me ${probe}`);
  for (let attempt = 0; attempt < 40; attempt += 1) {
    const url = await evaluate(connection, sessionId, "location.pathname");
    if (url !== "/login") return `${base}${url}`;
    await sleep(250);
  }
  throw new Error(`session for ${username} was rejected back to /login`);
}

async function settle(connection, sessionId, ms) {
  const start = Date.now();
  await sleep(ms);
  // 视图多为「挂载后立刻拉数据再渲染」，轮询到文本稳定为止，最多再等 3.5s。
  let previous = "";
  while (Date.now() - start < 3500) {
    const current = await evaluate(connection, sessionId, "document.body.innerText");
    if (current === previous) return current;
    previous = current;
    await sleep(250);
  }
  return previous;
}

/**
 * 只读交互探针：翻到下一页。
 * 分页是 usePagedResourceList / 各域 paginate 的公共出口，点一次即可证明
 * 「按钮可用 → 事件接线 → 页码状态回流到模板」整条链仍在；不写库，基线对比不受影响。
 */
async function runProbe(connection, sessionId, kind, settleMs) {
  if (kind !== "next-page") return null;
  const action = await evaluate(connection, sessionId, `(() => {
    const button = [...document.querySelectorAll("button")]
      .find((element) => element.innerText.trim().startsWith("下一页"));
    if (!button) return "absent";
    if (button.disabled) return "disabled";
    button.click();
    return "clicked";
  })()`);
  if (action !== "clicked") return { kind, action, pageLabel: "" };
  const text = await settle(connection, sessionId, settleMs);
  const match = normalize(text).match(/第\s*\d+\s*页|\d+\s*\/\s*\d+/);
  const masked = maskVolatile(normalize(text));
  return {
    kind, action, pageLabel: match ? match[0] : "",
    textHash: hash(masked.text), maskedAt: masked.masked,
  };
}

async function sweep() {
  const routes = JSON.parse(readFileSync(options.routes, "utf8"));
  const roles = options.roles.split(",").filter(Boolean);
  const userData = mkdtempSync(`${tmpdir()}/fams-arch-chrome-${options.label}-`);
  const chrome = spawn(CHROME, [
    "--headless=new", `--remote-debugging-port=${options.port}`, `--user-data-dir=${userData}`,
    "--no-first-run", "--no-default-browser-check", "--disable-gpu", "--disable-dev-shm-usage",
    "about:blank",
  ], { stdio: "ignore" });
  const rows = [];
  try {
    const browser = await connect(await waitForBrowser(options.port));
    for (const role of roles) {
      const page = await newPage(browser, "about:blank");
      const connection = browser;
      await connection.send("Page.enable", {}, page.sessionId);
      await connection.send("Runtime.enable", {}, page.sessionId);
      await connection.send("Network.enable", {}, page.sessionId);
      await connection.send("Log.enable", {}, page.sessionId);

      let capture = null;
      const events = [];
      connection.on((message) => {
        if (message.sessionId !== page.sessionId || !message.method || !capture) return;
        const params = message.params;
        if (message.method === "Runtime.consoleAPICalled" && (params.type === "error" || params.type === "warning")) {
          events.push(`${params.type}: ${(params.args || []).map((a) => a.value ?? a.description ?? a.type).join(" ")}`.slice(0, 220));
        } else if (message.method === "Runtime.exceptionThrown") {
          events.push(`exception: ${(params.exceptionDetails.exception?.description || params.exceptionDetails.text || "").slice(0, 220)}`);
        } else if (message.method === "Log.entryAdded" && params.entry.level === "error") {
          // 资源加载失败的日志条目只带 url，不带原因；按 url 过滤，避免同一噪声在两侧计数不一致。
          if (!params.entry.url.endsWith("/favicon.ico")) {
            events.push(`log: ${params.entry.text.slice(0, 220)}`);
          }
        } else if (message.method === "Network.responseReceived" && params.response.status >= 400) {
          events.push(`http ${params.response.status}: ${params.response.url.replace(/^https?:\/\/[^/]+/, "")}`);
        } else if (message.method === "Network.loadingFailed") {
          events.push(`netfail: ${params.errorText}`);
        }
      });

      capture = "login";
      try {
        await adoptSession(connection, page.sessionId, options.base, role, options.password);
      } catch (error) {
        const text = await evaluate(connection, page.sessionId, "document.body.innerText").catch(() => "");
        throw new Error(`login ${role} failed: ${error.message}; issues=${events.slice(0, 6).join(" | ")}; page=${text.replace(/\s+/g, " ").slice(0, 300)}`);
      }
      capture = null;
      if (process.env.ARCH_DEBUG) console.log(`login ${role} events: ${events.slice(0, 12).join(" | ")}`);
      events.splice(0, events.length);
      for (const route of routes) {
        capture = route.path;
        const collected = [];
        const swap = () => { const list = events.splice(0, events.length); collected.push(...list); };
        await connection.send("Page.navigate", { url: options.base + route.path }, page.sessionId);
        const text = await settle(connection, page.sessionId, Number(options.settle));
        swap();
        const finalUrl = await evaluate(connection, page.sessionId, "location.pathname");
        const structure = await evaluate(connection, page.sessionId, STRUCTURE_EXPRESSION);
        const inventory = JSON.parse(await evaluate(connection, page.sessionId, INVENTORY_EXPRESSION));
        const masked = maskVolatile(normalize(text));
        // favicon 缺失是本地栈的环境噪声，与视图渲染无关，不计入路由问题。
        const issues = collected.filter((line) => !line.includes("/favicon.ico"));
        const probe = await runProbe(connection, page.sessionId, route.probe, Number(options.settle));
        rows.push({
          role, name: route.name, path: route.path, finalUrl,
          chars: masked.text.length,
          textHash: hash(masked.text), structureHash: hash(structure),
          maskedAt: masked.masked,
          text: masked.text.slice(0, 4000), structureSample: structure.split("\n").slice(0, 40).join("\n"),
          controls: { total: inventory.total, disabled: inventory.disabled },
          buttons: inventory.buttons, fields: inventory.fields,
          probe,
          issues,
        });
        console.log(`${role} ${route.path} -> ${finalUrl} `
          + `${probe ? `probe:${probe.action}${probe.pageLabel ? `@${probe.pageLabel}` : ""} ` : ""}`
          + `${issues.length ? "ISSUES:" + issues.length : "clean"}`);
      }
      await browser.send("Target.closeTarget", { targetId: page.targetId });
    }
    chrome.kill("SIGKILL");
  } finally {
    if (!chrome.killed) chrome.kill("SIGKILL");
  }
  writeFileSync(options.out, JSON.stringify({ base: options.base, label: options.label, rows }, null, 2));
  const issues = rows.filter((row) => row.issues.length).length;
  console.log(`\nswept rows=${rows.length} routes_with_issues=${issues} report=${options.out}`);
}

function hash(text) {
  let a = 1;
  let b = 1;
  for (let index = 0; index < text.length; index += 1) {
    const code = text.charCodeAt(index);
    a = (a + code + index) % 1000000007;
    b = (b * 31 + code) % 1000000007;
  }
  return `${a.toString(36)}${b.toString(36)}`;
}

function diff() {
  const left = JSON.parse(readFileSync(options.left, "utf8"));
  const right = JSON.parse(readFileSync(options.right, "utf8"));
  const key = (row) => `${row.role} ${row.path}`;
  const byKey = new Map(right.rows.map((row) => [key(row), row]));
  const verdicts = { SAME: 0, STATUS_DIFF: 0, STRUCT_DIFF: 0, TEXT_DIFF: 0, CONTROL_DIFF: 0, ISSUE_DIFF: 0, PROBE_DIFF: 0, MISSING: 0 };
  const lines = [];
  for (const before of left.rows) {
    const after = byKey.get(key(before));
    if (!after) { verdicts.MISSING += 1; lines.push(`MISSING ${key(before)}`); continue; }
    const tags = [];
    if (before.finalUrl !== after.finalUrl) tags.push(["STATUS_DIFF", `route resolved ${before.finalUrl} -> ${after.finalUrl}`]);
    if (before.structureHash !== after.structureHash) tags.push(["STRUCT_DIFF", "DOM structure signature differs"]);
    if (before.textHash !== after.textHash) tags.push(["TEXT_DIFF", "rendered text differs"]);
    if (before.controls.total !== after.controls.total || before.controls.disabled !== after.controls.disabled) {
      tags.push(["CONTROL_DIFF", `controls ${before.controls.total}/${before.controls.disabled} -> ${after.controls.total}/${after.controls.disabled}`]);
    }
    if (before.issues.length !== after.issues.length) tags.push(["ISSUE_DIFF", `runtime issues ${before.issues.length} -> ${after.issues.length}`]);
    const probeOf = (row) => (row.probe ? `${row.probe.action}|${row.probe.pageLabel}|${row.probe.textHash || ""}` : "none");
    if (probeOf(before) !== probeOf(after)) tags.push(["PROBE_DIFF", `pagination probe ${probeOf(before)} -> ${probeOf(after)}`]);
    if (!tags.length) { verdicts.SAME += 1; continue; }
    for (const [tag] of tags) verdicts[tag] += 1;
    lines.push(`${tags.map(([tag, note]) => `${tag}(${note})`).join(", ")} :: ${key(before)}`);
    if (tags.some(([tag]) => tag === "TEXT_DIFF")) {
      lines.push(`   left(${before.chars} chars): ${snippet(before.text, after.text)}`);
      lines.push(`   right(${after.chars} chars): ${snippet(after.text, before.text)}`);
    }
    if (tags.some(([tag]) => tag === "STRUCT_DIFF")) {
      lines.push(`   structure left : ${before.structureSample.slice(0, 240)}`);
      lines.push(`   structure right: ${after.structureSample.slice(0, 240)}`);
    }
    if (tags.some(([tag]) => tag === "CONTROL_DIFF")) {
      lines.push(`   buttons left : ${before.buttons.join(" | ").slice(0, 240)}`);
      lines.push(`   buttons right: ${after.buttons.join(" | ").slice(0, 240)}`);
    }
    if (after.issues.length) lines.push(`   issues on right: ${after.issues.slice(0, 5).join(" | ")}`);
    if (before.issues.length) lines.push(`   issues on left : ${before.issues.slice(0, 5).join(" | ")}`);
  }
  console.log(lines.join("\n"));
  console.log(`\ncompared=${left.rows.length} ${JSON.stringify(verdicts)}`);
  return Object.entries(verdicts).every(([tag, count]) => tag === "SAME" || count === 0) ? 0 : 1;
}

function snippet(a, b) {
  let index = 0;
  while (index < a.length && a[index] === b[index]) index += 1;
  return JSON.stringify(a.slice(Math.max(0, index - 20), index + 60));
}

const exitCode = options.mode === "diff" ? diff() : await sweep();
process.exit(exitCode || 0);
