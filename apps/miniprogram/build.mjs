import { spawnSync } from "node:child_process";
import { cpSync, mkdirSync, rmSync, writeFileSync } from "node:fs";
import { fileURLToPath } from "node:url";
import { dirname, join } from "node:path";

const root = dirname(fileURLToPath(import.meta.url));
const origin = process.env.FAMS_MINI_API_ORIGIN ?? "";
if (origin && !/^https:\/\/[A-Za-z0-9.-]+(?::[0-9]{1,5})?\/?$/.test(origin)) {
    throw new Error("FAMS_MINI_API_ORIGIN must be an HTTPS origin");
}
const output = join(root, "dist");
rmSync(output, { recursive: true, force: true });
mkdirSync(output, { recursive: true });
const result = spawnSync("rtk", ["tsc", "-p", join(root, "tsconfig.build.json")], { cwd: root, stdio: "inherit" });
if (result.status !== 0) process.exit(result.status ?? 1);
cpSync(join(root, "native"), output, { recursive: true });
writeFileSync(join(output, "config.js"), `"use strict";\nexports.API_ORIGIN = ${JSON.stringify(origin)};\n`);
console.log(`Native mini package built; API origin ${origin ? "configured" : "unconfigured (requires external HTTPS resource)"}.`);
