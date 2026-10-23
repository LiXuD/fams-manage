import { mount, flushPromises } from "@vue/test-utils";
import { reactive } from "vue";
import { beforeEach, afterEach, it, expect, vi } from "vitest";
import RepairReassignmentPanel from "./RepairReassignmentPanel.vue";
import type { Repair } from "@/repair/api";
const state = vi.hoisted(() => ({ session: { user: { id: "1", roles: ["ASSET_ADMIN"] }, csrfToken: "csrf" } }));
vi.mock("@/auth/sessionStore", () => ({ useSessionStore: () => state.session }));
const large = "9007199254740995";
const repair = { id: "19", status: "WAREHOUSE_PROCESSING" } as Repair;
const response = (body: unknown, status = 200) => ({ ok: status === 200, status, json: async () => structuredClone(body) }) as Response;
const context = { snapshot: { id: "19", version: 4, ownershipVersion: 2, processorAccountId: "3", assetVersion: 1, lockVersion: 8,
  acceptedAt: "2026-10-23T00:00:00Z", physicalHandling: [{}], outstandingReturn: { id: "7", handover_status: "REFUSED" }, approvedTargetId: null, loan: null }, operators: [{ id: large, name: "接任库管" }] };
const page = () => mount(RepairReassignmentPanel, { props: { repair } });
const button = (wrapper: ReturnType<typeof page>, name: string) => wrapper.findAll("button").find(item => item.text() === name)!;
function wire(write: (input: RequestInit) => Promise<Response>) { vi.stubGlobal("fetch", vi.fn(async (_url: string, input: RequestInit) => input.method === "POST" ? write(input) : response(context))); }
async function fill(wrapper: ReturnType<typeof page>) { await button(wrapper,"核对实物阶段及接任库管").trigger("click"); await flushPromises();
  await wrapper.find("select").setValue(large); await wrapper.findAll("textarea")[0]!.setValue("原库管无法继续处理");
  await wrapper.findAll("textarea")[1]!.setValue("已实际核对设备待收回及所有未同步结果，完成本次实物交接凭证"); }
beforeEach(() => { state.session = reactive({ user: { id: "1", roles: ["ASSET_ADMIN"] }, csrfToken: "csrf" }); });
afterEach(() => { vi.unstubAllGlobals(); vi.restoreAllMocks(); });
it("requires explicit check, displays refused physical stage and confirms exact snapshot inline",async()=>{const writes:RequestInit[]=[];wire(async i=>{writes.push(i);return response({});});
 const wrapper=page();await fill(wrapper);expect(wrapper.text()).toContain("已拒收，等待实际收回");await button(wrapper,"核对本次维修转派").trigger("click");expect(writes).toHaveLength(0);
 await wrapper.find("input[type=checkbox]").setValue(true);await button(wrapper,"核对本次维修转派").trigger("click");expect(writes).toHaveLength(0);
 await button(wrapper,"确认保存维修转派").trigger("click");await flushPromises();expect(JSON.parse(writes[0]!.body as string)).toMatchObject({processorAccountId:large,snapshot:context.snapshot,unsynchronizedResultsChecked:true});expect(wrapper.emitted("changed")).toHaveLength(1);wrapper.unmount();});
it("unknown committed reply preserves exact original request after refreshed terminal props",async()=>{const writes:RequestInit[]=[];wire(async i=>{writes.push(i);if(writes.length===1)throw Error("committed reply lost");return response({});});
 const wrapper=page();await fill(wrapper);await wrapper.find("input[type=checkbox]").setValue(true);await button(wrapper,"核对本次维修转派").trigger("click");await button(wrapper,"确认保存维修转派").trigger("click");await flushPromises();
 await wrapper.setProps({repair:{...repair,status:"COMPLETED"}});await button(wrapper,"重试原维修转派").trigger("click");await flushPromises();expect(writes[1]).toEqual(writes[0]);wrapper.unmount();});
it("identity change discards prepared private handover evidence",async()=>{const writes:RequestInit[]=[];wire(async i=>{writes.push(i);return response({});});const wrapper=page();await fill(wrapper);await wrapper.find("input[type=checkbox]").setValue(true);await button(wrapper,"核对本次维修转派").trigger("click");state.session.user.id="8";await flushPromises();expect(wrapper.text()).not.toContain("确认保存维修转派");expect(wrapper.findAll("textarea")).toHaveLength(0);expect(writes).toHaveLength(0);wrapper.unmount();});
it("permission rejection hides the private snapshot and disables retry",async()=>{wire(async()=>response({code:"REPAIR_FORBIDDEN"},403));const wrapper=page();await fill(wrapper);await wrapper.find("input[type=checkbox]").setValue(true);await button(wrapper,"核对本次维修转派").trigger("click");await button(wrapper,"确认保存维修转派").trigger("click");await flushPromises();expect(wrapper.emitted("invalidated")).toHaveLength(1);expect(wrapper.text()).not.toContain("重试原维修转派");expect(wrapper.findAll("textarea")).toHaveLength(0);wrapper.unmount();});
