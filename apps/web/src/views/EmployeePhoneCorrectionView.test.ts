import { flushPromises, mount } from "@vue/test-utils";
import { reactive } from "vue";
import { beforeEach, describe, expect, it, vi } from "vitest";
import EmployeePhoneCorrectionView from "./EmployeePhoneCorrectionView.vue";
const mock = vi.hoisted(() => ({ request: vi.fn(),
  route: { query: { employeeId: "9007199254740993" } },
  session: { user: { id: "1", roles: ["SYSTEM_ADMIN"] } as { id: string; roles: string[] } | null } }));
vi.mock("vue-router", () => ({ useRoute: () => mock.route }));
vi.mock("@/auth/sessionStore", () => ({ useSessionStore: () => mock.session }));
vi.mock("@/masterdata/api", async original => ({
  ...await original<typeof import("@/masterdata/api")>(), masterDataRequest: mock.request,
}));
vi.mock("@/components/AppChrome.vue", () => ({ default: { template: "<main><slot /></main>" } }));
const employee = { id: "9007199254740993", employeeNo: "E01", displayName: "核验员工", status: "ACTIVE", version: 4 };
describe("employee phone correction", () => {
  beforeEach(() => {
    mock.session = reactive({ user: { id: "1", roles: ["SYSTEM_ADMIN"] } });
    mock.route = reactive({ query: { employeeId: "9007199254740993" } });
    mock.request.mockReset(); mock.request.mockResolvedValue(employee);
    vi.spyOn(window, "confirm").mockReturnValue(true);
  });
  it("keeps the verified payload, target and version for a lost reply retry", async () => {
    const writes: unknown[] = [];
    mock.request.mockImplementation(async (_path: string, operation?: unknown) => {
      if (operation) {
        writes.push(JSON.parse(JSON.stringify(operation)));
        if (writes.length === 1) throw new TypeError("lost committed reply");
        return { employeeId: employee.id, version: 5 };
      }
      return employee;
    });
    const wrapper = mount(EmployeePhoneCorrectionView, { global: { stubs: { RouterLink: true } } });
    await flushPromises();
    const region = wrapper.get('[aria-label="核验纠正员工"]');
    await region.get('input[type="tel"]').setValue("13800000009");
    await region.get('input[maxlength="128"]').setValue("核验工单 TEST-01");
    await region.get("textarea").setValue("登记号码纠正");
    await region.get("form").trigger("submit"); await flushPromises();
    expect(region.get("fieldset").attributes("disabled")).toBeDefined();
    await region.get("form").trigger("submit"); await flushPromises();
    expect(writes).toHaveLength(2); expect(writes[0]).toEqual(writes[1]);
    expect(writes[0]).toMatchObject({ path: "/employees/9007199254740993/phone-correction", method: "POST", version: 4,
      body: { phone: "13800000009", verificationMethod: "核验工单 TEST-01", reason: "登记号码纠正" } });
    expect(wrapper.text()).toContain("结果版本 5");
    expect((region.get('input[type="tel"]').element as HTMLInputElement).value).toBe("");
  });
  it("requires the next employee to be read after editing the target identifier", async () => {
    const wrapper = mount(EmployeePhoneCorrectionView, { global: { stubs: { RouterLink: true } } });
    await flushPromises();
    await wrapper.get('input[inputmode="numeric"]').setValue("44");
    expect(wrapper.find("form").exists()).toBe(false);
    expect(mock.request).toHaveBeenCalledTimes(1);
  });
  it("denies business administrators before reading employee identity", async () => {
    mock.session.user = { id: "2", roles: ["ASSET_ADMIN"] };
    const wrapper = mount(EmployeePhoneCorrectionView, { global: { stubs: { RouterLink: true } } });
    await flushPromises();
    expect(mock.request).not.toHaveBeenCalled();
    expect(wrapper.find("form").exists()).toBe(false);
  });
});
