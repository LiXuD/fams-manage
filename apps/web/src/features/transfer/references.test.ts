import { describe, expect, it } from "vitest";
import { buildTransferTarget, resolveReference } from "./references";
import type { TransferChoice } from "@fams/api-types/transfer";

const choice = (id: string, version: number): TransferChoice => ({ id, name: `名称${id}`, version });
const location = choice("L", 8), owner = choice("O", 9), warehouse = choice("W", 10);
const using = choice("U", 5), employee = choice("E", 6);

describe("调拨目标装配纯函数", () => {
  it("在库目标按仓位保管装配，仓库版本进引用版本", () => {
    expect(buildTransferTarget({ stock: true, custodyType: "PERSONAL", location, owner, warehouse })).toEqual({
      owningOrganizationId: "O", custodyType: "STORAGE", usingOrganizationId: null, employeeId: null,
      warehouseId: "W", locationId: "L", referenceVersions: { location: 8, owningOrganization: 9, warehouse: 10 } });
  });

  it("在用目标保留保管方式与部门责任人，仓库位归空", () => {
    expect(buildTransferTarget({ stock: false, custodyType: "SHARED", location, usingOrganization: using, employee })).toEqual({
      owningOrganizationId: null, custodyType: "SHARED", usingOrganizationId: "U", employeeId: "E",
      warehouseId: null, locationId: "L", referenceVersions: { location: 8, usingOrganization: 5, employee: 6 } });
  });

  it("resolveReference 只按 id 命中，缺目录或未选时返回 undefined", () => {
    expect(resolveReference([location, warehouse], "W")).toBe(warehouse);
    expect(resolveReference(undefined, "W")).toBeUndefined();
    expect(resolveReference([location], "")).toBeUndefined();
  });
});
