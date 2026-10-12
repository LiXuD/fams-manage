import { flushPromises, mount } from "@vue/test-utils";
import { describe, expect, it, vi } from "vitest";
import RequisitionReassignment from "./RequisitionReassignment.vue";
const api = vi.hoisted(() => ({ request: vi.fn() }));
vi.mock("@/loan/api", () => ({ loanRequest: api.request, loanMessage: () => "结果尚未确认" }));
describe("controlled requisition reassignment", () => {
  it("requires a checked physical snapshot and retries the identical request after a lost response", async () => {
    const writes: unknown[] = []; let lose = true;
    api.request.mockImplementation(async (_path: string, mutation?: unknown) => {
      if (!mutation) return { id: "8", version: 7, ownershipVersion: 2, processorAccountId: "1",
        operators: [{ id: "3", name: "接任库管" }],
        items: [{ id: "9", version: 4, status: "WAITING_DELIVERY", assetVersion: 5, lockVersion: 6, assetCode: "FA1", warehouseId: "1", locationId: "2" }] };
      writes.push(structuredClone(mutation));
      if (lose) { lose = false; throw new Error("lost after commit"); }
      return {};
    });
    const wrapper = mount(RequisitionReassignment, { props: { id: "8", version: 7 } });
    const button = (text: string) => wrapper.findAll("button").find(b => b.text() === text)!;
    await button("加载 / 重新核对转派清单").trigger("click"); await flushPromises();
    await button("确认转派整单").trigger("click"); expect(writes).toHaveLength(0);
    await wrapper.find("select").setValue("3");
    await wrapper.findAll("textarea")[0]!.setValue("原库管请假");
    await wrapper.findAll("textarea")[1]!.setValue("已交出至二楼，核对资产锁及交接记录");
    await wrapper.find('input[type="checkbox"]').setValue(true);
    await button("确认转派整单").trigger("click"); await flushPromises();
    expect(wrapper.find("select").attributes("disabled")).toBeDefined();
    await button("原请求重试转派").trigger("click"); await flushPromises();
    expect(writes).toHaveLength(2); expect(writes[1]).toEqual(writes[0]);
    expect(writes[0]).toMatchObject({ path: "/requisitions/8/reassignment", version: 7,
      body: { processorAccountId: "3", ownershipVersion: 2, unsynchronizedResultsChecked: true,
        items: [{ id: "9", version: 4, status: "WAITING_DELIVERY", assetVersion: 5, lockVersion: 6 }] } });
    expect(wrapper.emitted("saved")).toHaveLength(1); wrapper.unmount();
  });
});
