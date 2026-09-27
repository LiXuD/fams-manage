import { createPinia } from "pinia";
import { mount, flushPromises } from "@vue/test-utils";
import { beforeEach, describe, expect, it, vi } from "vitest";
import { useSessionStore } from "@/auth/sessionStore";
import { listAttachments, uploadAttachment, downloadAttachment, type Attachment } from "@/attachment/api";
import type { InboundReceipt } from "@/inbound/api";
import AttachmentPanel from "./AttachmentPanel.vue";

vi.mock("@/attachment/api", () => ({ listAttachments: vi.fn(), uploadAttachment: vi.fn(), downloadAttachment: vi.fn() }));
const receipt: InboundReceipt = {
  id: "10", version: 4, receiptNumber: "IN10", status: "DRAFT", businessDate: "2026-09-27",
  createdBy: { id: "7", username: "warehouse" }, items: [],
};
const attachment: Attachment = { objectId: "attachment-id", filename: "doc.pdf", contentType: "application/pdf",
  sizeBytes: 8, scanStatus: "CLEAN", businessVersion: 5 };

function mountPanel(value = receipt) {
  const pinia = createPinia();
  const store = useSessionStore(pinia);
  store.user = { id: "7", username: "warehouse", roles: ["WAREHOUSE_OPERATOR"],
    requiresInitialPasswordChange: false } as NonNullable<typeof store.user>;
  return mount(AttachmentPanel, { props: { receipt: value }, global: { plugins: [pinia] } });
}

describe("AttachmentPanel", () => {
  beforeEach(() => {
    vi.clearAllMocks();
    vi.mocked(listAttachments).mockResolvedValue([]);
  });
  it("preserves original upload key and version on retry and emits the new version", async () => {
    vi.mocked(uploadAttachment).mockRejectedValueOnce(new Error("连接中断")).mockResolvedValueOnce(attachment);
    const wrapper = mountPanel();
    await flushPromises();
    const file = new File(["%PDF-1.7"], "doc.pdf", { type: "application/pdf" });
    Object.defineProperty(wrapper.get("input[type=file]").element, "files", { value: [file] });
    await wrapper.get("input[type=file]").trigger("change");
    await wrapper.get("form").trigger("submit");
    await flushPromises();
    expect(wrapper.get("[role=alert]").text()).toContain("连接中断");
    await wrapper.get("form").trigger("submit");
    await flushPromises();
    const calls = vi.mocked(uploadAttachment).mock.calls;
    expect(calls).toHaveLength(2);
    expect(calls[0]).toEqual(calls[1]);
    expect(calls[0].slice(0, 3)).toEqual(["10", 4, file]);
    expect(wrapper.emitted("uploaded")).toEqual([[5]]);
    expect(wrapper.emitted("busy")).toEqual([[true], [false], [true], [false]]);
  });
  it("shows escaped filenames and scan states and blocks quarantined downloads and other owners", async () => {
    vi.mocked(listAttachments).mockResolvedValue([
      { ...attachment, filename: "<img src=x onerror=alert(1)>", scanStatus: "UNAVAILABLE" },
    ]);
    const wrapper = mountPanel({ ...receipt, status: "WAREHOUSE_PROCESSING",
      currentProcessor: { processor: { id: "8", username: "other" } } });
    await flushPromises();
    expect(wrapper.find("form").exists()).toBe(false);
    expect(wrapper.text()).toContain("扫描不可用 · 已隔离");
    expect(wrapper.find("img").exists()).toBe(false);
    const download = wrapper.findAll("button").find((button) => button.text() === "下载")!;
    expect(download.attributes("disabled")).toBeDefined();
    await download.trigger("click");
    expect(downloadAttachment).not.toHaveBeenCalled();
  });
});
