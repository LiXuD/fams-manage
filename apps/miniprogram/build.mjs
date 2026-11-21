import { spawnSync } from "node:child_process";
import { cpSync, mkdirSync, readdirSync, readFileSync, rmSync, writeFileSync } from "node:fs";
import { fileURLToPath } from "node:url";
import { dirname, join } from "node:path";

const root = dirname(fileURLToPath(import.meta.url));
const origin = process.env.FAMS_MINI_API_ORIGIN ?? "";
if (origin && !/^https:\/\/[A-Za-z0-9.-]+(?::[0-9]{1,5})?\/?$/.test(origin)) {
    throw new Error("FAMS_MINI_API_ORIGIN must be an HTTPS origin");
}
// A bare wx:if/wx:elif/wx:for value is a truthy string literal, so conditions silently always render.
for (const entry of readdirSync(join(root, "native"), { recursive: true, encoding: "utf8" })) {
    if (!entry.endsWith(".wxml")) continue;
    const text = readFileSync(join(root, "native", entry), "utf8");
    const bare = [...text.matchAll(/wx:(if|elif|for)="([^"]*)"/g)].filter(match => !match[2].includes("{{"));
    if (bare.length) throw new Error(`WXML ${entry}: wx:${bare[0][1]} requires a {{}} expression`);
    // WXML expression parsing does not decode entities; &&, < and > must be raw inside {{}}.
    const entityCount = (text.match(/&(amp|lt|gt);/g) || []).length;
    if (entityCount) throw new Error(`WXML ${entry}: ${entityCount} entity/entities must be written as raw &&, < or > inside {{}}`);
    for (const match of text.matchAll(/\{\{([\s\S]*?)\}\}/g)) {
        const open = (match[1].match(/\(/g) || []).length, close = (match[1].match(/\)/g) || []).length;
        if (open !== close) throw new Error(`WXML ${entry}: unbalanced parentheses (${open}/${close}) inside {{}}`);
    }
}
const output = join(root, "dist");
rmSync(output, { recursive: true, force: true });
mkdirSync(output, { recursive: true });
const result = spawnSync("rtk", ["tsc", "-p", join(root, "tsconfig.build.json")], { cwd: root, stdio: "inherit" });
if (result.status !== 0) process.exit(result.status ?? 1);
cpSync(join(root, "native"), output, { recursive: true });
// Only a real enterprise AppID may be written: an absent appid lets the tool pick its own tourist
// session, while a literal "touristappid" triggers a failing Change AppID flow.
const localOrigin = /^https:\/\/(localhost|127\.0\.0\.1)(:[0-9]{1,5})?\/?$/.test(origin);
if (localOrigin || process.env.FAMS_MINI_APPID) {
    const projectConfigPath = join(output, "project.config.json");
    const projectConfig = JSON.parse(readFileSync(projectConfigPath, "utf8"));
    if (process.env.FAMS_MINI_APPID) projectConfig.appid = process.env.FAMS_MINI_APPID;
    // Local integration uses a self-signed certificate; the simulator must skip domain and TLS checks.
    if (localOrigin) projectConfig.setting = { ...projectConfig.setting, urlCheck: false };
    writeFileSync(projectConfigPath, JSON.stringify(projectConfig, null, 2) + "\n");
}
writeFileSync(join(output, "config.js"), `"use strict";\nexports.API_ORIGIN = ${JSON.stringify(origin)};\n`);
console.log(`Native mini package built; API origin ${origin ? "configured" : "unconfigured (requires external HTTPS resource)"}.`);
