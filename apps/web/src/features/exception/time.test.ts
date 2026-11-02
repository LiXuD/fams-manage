import { describe, expect, it } from "vitest";
import { formatProxyTime } from "./time";

describe("signed receipt times", () => {
  it("interprets legacy bare server timestamps as UTC while retaining explicit offsets", () => {
    const local = new Date("2026-10-23T17:44:00Z").toLocaleString();
    expect(formatProxyTime("2026-10-23T17:44:00")).toBe(local);
    expect(formatProxyTime("2026-10-23T17:44:00Z")).toBe(local);
    expect(formatProxyTime("2026-10-24T01:44:00+08:00")).toBe(local);
    expect(formatProxyTime("")).toBe("未记录");
    expect(formatProxyTime("invalid timestamp")).toBe("时间无效");
  });
});
