import { flushPromises, mount } from "@vue/test-utils";
import { beforeEach, describe, expect, it, vi } from "vitest";
import AccountAuthorizationPanel from "./AccountAuthorizationPanel.vue";

const mock = vi.hoisted(() => ({ request: vi.fn(), session: { csrfToken: "csrf" } }));
vi.mock("@/auth/sessionStore", () => ({ useSessionStore: () => mock.session }));
vi.mock("@/api/business", async original => ({
  ...await original<typeof import("@/api/business")>(), businessRequest: mock.request,
}));
const authorization = { id: "0", role: "AUDIT_READ_ONLY", status: "REVOKED", version: 3,
  dataScopes: [], fieldPermissions: [] };
const props = { accountId: "9007199254740993", username: "auditor", role: "AUDIT_READ_ONLY" as const, blocked: false };

describe("account authorization", () => {
  beforeEach(() => {
    mock.request.mockReset();
    mock.request.mockResolvedValue(authorization);
    vi.spyOn(window, "confirm").mockReturnValue(true);
    vi.stubGlobal("fetch", vi.fn());
  });

  it("uses the authorization generation and preserves the exact payload on an unknown result", async () => {
    const fetcher = vi.mocked(fetch);
    fetcher.mockRejectedValueOnce(new TypeError("reply lost")).mockResolvedValueOnce(
      new Response(JSON.stringify({ ...authorization, id: "7", status: "ACTIVE", version: 4 })));
    const wrapper = mount(AccountAuthorizationPanel, { props });
    await wrapper.get("button").trigger("click"); await flushPromises();
    await wrapper.findAll("button").find(button => button.text() === "本人")!.trigger("click");
    await wrapper.get('input[value="BULK_EXPORT"]').setValue(true);
    await wrapper.get("textarea").setValue("限定授权");
    await wrapper.findAll("button").find(button => button.text() === "保存授权")!.trigger("click");
    await flushPromises();
    await wrapper.findAll("button").find(button => button.text() === "原请求重试授权")!.trigger("click");
    await flushPromises();
    expect(fetcher).toHaveBeenCalledTimes(2);
    expect(fetcher.mock.calls[0]).toEqual(fetcher.mock.calls[1]);
    const [url, request] = fetcher.mock.calls[0];
    expect(url).toBe("/api/v1/system-accounts/9007199254740993/authorization/AUDIT_READ_ONLY");
    expect(request).toMatchObject({ method: "PATCH", credentials: "include",
      headers: { "If-Match-Version": "3", "X-CSRF-Token": "csrf" } });
    expect(JSON.parse(request!.body as string)).toEqual({ reason: "限定授权", fieldPermissions: ["BULK_EXPORT"],
      dataScopes: [{ scopeType: "SELF", organizations: [], warehouses: [] }] });
    expect(wrapper.text()).toContain("当前状态 ACTIVE · 版本 4");
  });

  it("resolves a specified organization through the current catalog version before saving", async () => {
    mock.request.mockResolvedValueOnce(authorization).mockResolvedValueOnce({
      id: "9007199254740994", version: 11, name: "被审计部门", status: "ACTIVE",
    });
    vi.mocked(fetch).mockResolvedValue(new Response(JSON.stringify({ ...authorization, version: 4 })));
    const wrapper = mount(AccountAuthorizationPanel, { props });
    await wrapper.get("button").trigger("click"); await flushPromises();
    await wrapper.findAll("button").find(button => button.text() === "指定组织")!.trigger("click");
    await wrapper.get('input[inputmode="numeric"]').setValue("9007199254740994");
    await wrapper.findAll("button").find(button => button.text() === "读取并添加有效资料")!.trigger("click");
    await flushPromises();
    expect(wrapper.text()).toContain("被审计部门");
    await wrapper.get("textarea").setValue("指定审计部门");
    await wrapper.findAll("button").find(button => button.text() === "保存授权")!.trigger("click");
    await flushPromises();
    expect(JSON.parse(vi.mocked(fetch).mock.calls[0][1]!.body as string).dataScopes).toEqual([
      { scopeType: "SPECIFIED_ORGANIZATIONS", organizations: [{ id: "9007199254740994", version: 11 }], warehouses: [] },
    ]);
  });
});
