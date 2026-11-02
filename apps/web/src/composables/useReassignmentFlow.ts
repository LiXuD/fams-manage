import { shallowRef, watch } from "vue";
import { businessMessage, businessRequest, type BusinessMutation } from "@/api/business";

/**
 * 整单处理权转派的工作流：读快照 → 填表 → 提交，并守住「丢失应答后用同一请求原样重试」这条不变量。
 *
 * 四个业务域（入库单、调拨、领用申请、退库单）的转派在流程上完全一致，差别只在端点集合名、
 * 明细字段映射与明细的展示标记。这里只收敛流程；明细类型、字典与模板留在各域。
 */

export interface ReassignmentSnapshot<TItem> {
  id: string;
  version: number;
  ownershipVersion: number;
  processorAccountId: string;
  operators: { id: string; name: string }[];
  items: TItem[];
}

export interface ReassignmentPayloadItem {
  id: string;
  version: number;
  status: string;
  lockVersion: number;
  assetVersion?: number;
}

export interface ReassignmentFlowOptions<TItem> {
  /** 端点集合名，如 inbound-receipts */
  collection: string;
  /** 域明细到转派载荷的映射；不同域的明细字段不一致 */
  mapItems: (item: TItem) => ReassignmentPayloadItem;
  onSaved: () => void;
}

export function useReassignmentFlow<TItem>(
  props: { id: string; version: number },
  options: ReassignmentFlowOptions<TItem>,
) {
  const snapshot = shallowRef<ReassignmentSnapshot<TItem> | null>(null);
  const target = shallowRef(""), reason = shallowRef(""), evidence = shallowRef("");
  const checked = shallowRef(false), busy = shallowRef(false), error = shallowRef(""), retryable = shallowRef(false);
  let pending: BusinessMutation | null = null, generation = 0;

  const endpoint = (id: string) => `/${options.collection}/${id}/reassignment`;

  watch(() => [props.id, props.version], () => {
    generation++; snapshot.value = null; pending = null; retryable.value = false;
    target.value = ""; reason.value = ""; evidence.value = ""; checked.value = false;
  });

  async function inspect() {
    if (busy.value) return;
    const turn = ++generation; busy.value = true; error.value = "";
    try {
      const result = await businessRequest<ReassignmentSnapshot<TItem>>(endpoint(props.id));
      if (turn !== generation) return;
      snapshot.value = result; pending = null; retryable.value = false; checked.value = false;
    } catch (e) { if (turn === generation) error.value = businessMessage(e); }
    finally { busy.value = false; }
  }

  async function submit() {
    if (busy.value || !snapshot.value) return;
    if (!pending && (!target.value || !reason.value.trim() || !evidence.value.trim() || !checked.value)) {
      error.value = "请选择接任库管，填写原因及交接凭证，并核对未同步结果。"; return;
    }
    const current = snapshot.value;
    pending ??= { path: endpoint(current.id), version: current.version, key: crypto.randomUUID(),
      body: { processorAccountId: target.value, ownershipVersion: current.ownershipVersion,
        reason: reason.value.trim(), evidence: evidence.value.trim(), unsynchronizedResultsChecked: true,
        items: current.items.map(options.mapItems) } };
    busy.value = true; error.value = ""; const turn = generation;
    try {
      await businessRequest(pending.path, pending);
      if (turn !== generation) return;
      pending = null; retryable.value = false; snapshot.value = null; options.onSaved();
    } catch (e) { if (turn === generation) { error.value = businessMessage(e); retryable.value = true; } }
    finally { busy.value = false; }
  }

  return { snapshot, target, reason, evidence, checked, busy, error, retryable, inspect, submit };
}
