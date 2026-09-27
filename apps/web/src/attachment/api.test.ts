import { createPinia, setActivePinia } from "pinia";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { useSessionStore } from "@/auth/sessionStore";
import { downloadAttachment, uploadAttachment, type Attachment } from "./api";

describe("attachment transport", () => {
  beforeEach(() => { setActivePinia(createPinia()); useSessionStore().csrfToken = "test-csrf"; });
  afterEach(() => vi.unstubAllGlobals());
  it("sends multipart with the caller replay key and version without a manual content type", async () => {
    const fetch = vi.fn().mockResolvedValue(new Response(JSON.stringify({ businessVersion: 5 }), { status: 201 }));
    vi.stubGlobal("fetch", fetch);
    const file = new File(["%PDF-1.7"], "doc.pdf", { type: "application/pdf" });
    await uploadAttachment("10", 4, file, "original-upload-key");
    const init = fetch.mock.calls[0][1] as RequestInit;
    expect(init.credentials).toBe("include");
    expect(init.headers).toEqual({ "X-CSRF-Token": "test-csrf", "Idempotency-Key": "original-upload-key",
      "If-Match-Version": "4" });
    expect(init.body).toBeInstanceOf(FormData);
    expect((init.body as FormData).get("file")).toBe(file);
  });
  it("does not fetch an external grant address or allow quarantined content", async () => {
    const fetch = vi.fn().mockResolvedValue(new Response(JSON.stringify({ url: "https://other.example/content" })));
    vi.stubGlobal("fetch", fetch);
    const attachment: Attachment = { objectId: "id", filename: "doc.pdf", contentType: "application/pdf",
      sizeBytes: 8, scanStatus: "CLEAN", businessVersion: 5 };
    await expect(downloadAttachment(attachment)).rejects.toThrow("下载地址无效");
    expect(fetch).toHaveBeenCalledTimes(1);
    await expect(downloadAttachment({ ...attachment, scanStatus: "INFECTED" })).rejects.toThrow("暂不可下载");
    expect(fetch).toHaveBeenCalledTimes(1);
  });
});
