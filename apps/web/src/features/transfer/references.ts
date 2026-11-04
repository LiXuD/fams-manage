import type { TransferChoice, TransferTarget } from "@fams/api-types/transfer";

/**
 * ARCH-10 基础目录扇出与目标引用装配的共享纯实现（Keep Utilities as Utilities：纯计算不包成 composable）。
 *
 * loadCatalogPages 只承担「从 page=1 起按 pageSize=200 逐页累积，直到某页不满 200 条」这一不变量；
 * 请求客户端、基础路径与组织参数由调用方传入，拼出的 URL 与原各文件内联实现逐字一致。
 * 迟到抑制（turn/isCurrent）、busy 归属与选项过滤仍留在各组件，因各处判据并不相同。
 */

export type CatalogRequest = <R>(path: string) => Promise<R>;

export async function loadCatalogPages<T>(request: CatalogRequest, basePath: string, kind: string,
  organizationId?: string): Promise<T[]> {
  const all: T[] = []; let page = 1, part: T[];
  do { part = (await request<{ items: T[] }>(`${basePath}/${kind}?page=${page++}&pageSize=200${organizationId ? `&organizationId=${organizationId}` : ""}`)).items; all.push(...part); }
  while (part.length === 200);
  return all;
}

export function resolveReference<T extends { id: string }>(choices: readonly T[] | undefined,
  id: string | null | undefined): T | undefined {
  return choices?.find(row => row.id === id);
}

export function buildTransferTarget(parts: { stock: boolean; custodyType: string; location: TransferChoice;
  owner?: TransferChoice | null; warehouse?: TransferChoice | null;
  usingOrganization?: TransferChoice | null; employee?: TransferChoice | null }): TransferTarget {
  const versions: Record<string, number> = { location: parts.location.version };
  if (parts.owner) versions.owningOrganization = parts.owner.version;
  if (parts.stock) versions.warehouse = parts.warehouse!.version;
  else { versions.usingOrganization = parts.usingOrganization!.version; versions.employee = parts.employee!.version; }
  return { owningOrganizationId: parts.owner?.id ?? null, custodyType: parts.stock ? "STORAGE" : parts.custodyType,
    usingOrganizationId: parts.stock ? null : parts.usingOrganization!.id, employeeId: parts.stock ? null : parts.employee!.id,
    warehouseId: parts.stock ? parts.warehouse!.id : null, locationId: parts.location.id, referenceVersions: versions };
}
