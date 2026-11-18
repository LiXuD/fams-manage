<script setup lang="ts">
import { computed, ref, shallowRef, watch } from "vue";
import AppChrome from "@/components/AppChrome.vue";
import { useSessionStore } from "@/auth/sessionStore";
import { useConfirmedSubmit, type Submission } from "@/composables/useConfirmedSubmit";
import { useRequestLiveness } from "@/composables/useRequestLiveness";
import { masterDataRequest, MasterDataApiError, type MasterDataMutation } from "@/features/masterdata/api";

const catalogNames = { "asset-categories": "资产分类", brands: "品牌", manufacturers: "厂商",
  warehouses: "仓库", locations: "位置" } as const;
type Catalog = keyof typeof catalogNames;
type ReferenceField = "parentCategory" | "defaultManufacturer" | "parentLocation" | "warehouse";
interface Reference { id: string; version: number }
interface ExtensionField { key: string; label: string; type: "TEXT" | "NUMBER" | "DATE" | "BOOLEAN"; required: boolean }
type CatalogRow = Partial<Record<ReferenceField, Reference>> & {
  id: string; code: string; name: string; status: "ACTIVE" | "DISABLED"; version: number;
  description?: string; suggestedUnit?: string; numberingPrefix?: string;
  type?: string; extensionFieldTemplate?: ExtensionField[];
};
const referenceDefinitions: Record<Catalog, { field: ReferenceField; catalog: Catalog; label: string }[]> = {
  "asset-categories": [{ field: "parentCategory", catalog: "asset-categories", label: "上级分类编号" }],
  brands: [{ field: "defaultManufacturer", catalog: "manufacturers", label: "默认厂商编号" }],
  manufacturers: [], warehouses: [],
  locations: [{ field: "parentLocation", catalog: "locations", label: "上级位置编号" },
    { field: "warehouse", catalog: "warehouses", label: "所属仓库编号" }],
};
const locationTypes = { BUILDING: "楼宇", FLOOR: "楼层", ROOM: "房间", AREA: "区域", BIN: "库位" } as const;
/** 保存意图只带路径、方法、版本与请求体；幂等键一律由 useConfirmedSubmit 底座生成，重试不换键。 */
type CatalogIntent = Pick<MasterDataMutation, "path" | "method" | "version" | "body">;
interface CatalogRowView extends CatalogRow { referenceLines: { field: ReferenceField; text: string }[] }

const session = useSessionStore();
const identity = computed(() => session.user?.roles.includes("SYSTEM_ADMIN") ? session.user.id : "");
const kind = shallowRef<Catalog>("asset-categories");
const rows = ref<CatalogRow[]>([]);
const page = shallowRef(1); const total = shallowRef(0);
const selected = shallowRef<CatalogRow | null>(null);
const formOpen = shallowRef(false);
const code = shallowRef(""); const name = shallowRef(""); const description = shallowRef("");
const suggestedUnit = shallowRef(""); const numberingPrefix = shallowRef("");
const locationType = shallowRef("ROOM");
const references = ref<Record<ReferenceField, string>>({ parentCategory: "", defaultManufacturer: "", parentLocation: "", warehouse: "" });
const extensions = ref<ExtensionField[]>([]);
const reason = shallowRef(""); const error = shallowRef(""); const notice = shallowRef("");
const busy = shallowRef(false);
const liveness = useRequestLiveness(() => [identity.value, kind.value]);
/** 确定性失败判据取本域 MasterDataApiError 的 4xx 区间：清意在途意图，允许重新起草。 */
const deterministic = (cause: unknown): boolean => cause instanceof MasterDataApiError
  && cause.status >= 400 && cause.status < 500;
const { pending: pendingSave, prepare: draftSave, confirm: adoptSave, settle: settleSave,
  fail: failSave, reset: resetSave } = useConfirmedSubmit<CatalogIntent>({ failure: deterministic });

const catalogNameText = computed(() => catalogNames[kind.value]);
const isLocations = computed(() => kind.value === "locations");
const isCategories = computed(() => kind.value === "asset-categories");
const activeReferences = computed(() => referenceDefinitions[kind.value]);
const createLabel = computed(() => `新建${catalogNames[kind.value]}`);
const formTitle = computed(() => `${selected.value ? "维护" : "新建"}${catalogNames[kind.value]}`);
const referenceHintVisible = computed(() => !!selected.value && activeReferences.value.length > 0);
const controlsDisabled = computed(() => busy.value || pendingSave.value !== null);
const retryDisabled = computed(() => busy.value);
const addExtensionDisabled = computed(() => extensions.value.length >= 32);
const statusActionDisabled = computed(() => !reason.value.trim());
const statusActionLabel = computed(() => selected.value?.status === "ACTIVE" ? "停用" : "启用");
const statusActionKind = computed(() => selected.value?.status === "ACTIVE" ? "danger" : "secondary");
const prevDisabled = computed(() => page.value <= 1 || busy.value || pendingSave.value !== null);
const nextDisabled = computed(() => page.value * 20 >= total.value || busy.value || pendingSave.value !== null);
const catalogRows = computed<CatalogRowView[]>(() => rows.value.map(row => ({
  ...row,
  referenceLines: activeReferences.value.map(definition => ({ field: definition.field,
    text: `${definition.label} ${row[definition.field]?.id ?? "未设置"}` })),
})));

function problem(cause: unknown): string {
  if (cause instanceof MasterDataApiError) {
    if (cause.status === 401 || cause.status === 403) return "当前身份或系统管理员权限已失效。";
    if (cause.status === 409) return "资料版本、编码、层级或业务引用冲突，请刷新核对后再操作。";
    if (cause.status === 400 || cause.status === 404) return "请核对目录资料、关联编号及分类扩展字段。";
  }
  return "操作结果尚未确认，请使用原请求重试。";
}

function open(row: CatalogRow | null): void {
  selected.value = row; formOpen.value = true;
  code.value = row?.code ?? ""; name.value = row?.name ?? ""; description.value = row?.description ?? "";
  suggestedUnit.value = row?.suggestedUnit ?? ""; numberingPrefix.value = row?.numberingPrefix ?? "";
  locationType.value = row?.type ?? "ROOM";
  extensions.value = (row?.extensionFieldTemplate ?? []).map(field => ({ ...field }));
  for (const definition of referenceDefinitions[kind.value]) references.value[definition.field] = row?.[definition.field]?.id ?? "";
  reason.value = ""; error.value = ""; notice.value = "";
}

async function load(): Promise<void> {
  if (!identity.value || busy.value || pendingSave.value) return;
  const turn = liveness.begin();
  busy.value = true; error.value = "";
  try {
    const result = await masterDataRequest<{ items: CatalogRow[]; total: number }>(
      `/${kind.value}?page=${page.value}&pageSize=20`);
    if (!liveness.isCurrent(turn)) return;
    rows.value = result.items; total.value = result.total;
    if (selected.value) {
      const row = result.items.find(item => item.id === selected.value?.id);
      if (row) open(row);
      else { selected.value = null; formOpen.value = false; }
    }
  } catch (cause) {
    if (liveness.isCurrent(turn)) error.value = problem(cause);
  } finally {
    if (liveness.isCurrent(turn)) busy.value = false;
  }
}

async function execute(): Promise<void> {
  if (!identity.value || !pendingSave.value || busy.value) return;
  const operation = pendingSave.value;
  const turn = liveness.begin();
  busy.value = true; error.value = "";
  try {
    const result = await masterDataRequest<CatalogRow>(operation.payload.path,
      { ...operation.payload, key: operation.key });
    if (!liveness.isCurrent(turn)) return;
    settleSave(); selected.value = null; formOpen.value = false;
    notice.value = `${result.name} 已保存，状态 ${result.status}，版本 ${result.version}。`;
    busy.value = false;
    await load();
  } catch (cause) {
    if (!liveness.isCurrent(turn)) return;
    failSave(cause);
    error.value = problem(cause);
  } finally {
    if (liveness.isCurrent(turn)) busy.value = false;
  }
}

async function save(): Promise<void> {
  if (!identity.value || busy.value || pendingSave.value || !code.value.trim() || !name.value.trim()) return;
  const row = selected.value;
  const turn = liveness.begin();
  const body: Record<string, unknown> = { code: code.value.trim(), name: name.value.trim(), description: description.value };
  if (kind.value === "asset-categories") {
    body.suggestedUnit = suggestedUnit.value; body.numberingPrefix = numberingPrefix.value;
    body.extensionFieldTemplate = extensions.value.map(field => ({ ...field }));
  }
  if (kind.value === "locations") body.type = locationType.value;
  let adopted: Submission<CatalogIntent> | null = null;
  busy.value = true; error.value = "";
  try {
    for (const definition of referenceDefinitions[kind.value]) {
      const id = references.value[definition.field];
      if (id && id !== row?.[definition.field]?.id) {
        if (!/^[1-9]\d*$/.test(id)) throw new MasterDataApiError(400, "CATALOG_REFERENCE_INVALID");
        const reference = await masterDataRequest<CatalogRow>(`/${definition.catalog}/${id}`);
        if (!liveness.isCurrent(turn)) return;
        if (reference.status !== "ACTIVE") throw new MasterDataApiError(409, "CATALOG_REFERENCE_DISABLED");
        body[definition.field] = { id: reference.id, version: reference.version };
      }
    }
    if (!window.confirm(`${row ? "更新" : "新建"}${catalogNames[kind.value]} ${name.value.trim()}`)) return;
    draftSave(() => ({ path: `/${kind.value}${row ? "/" + row.id : ""}`,
      method: row ? "PATCH" : "POST", version: row?.version ?? 0, body }));
    adopted = adoptSave();
  } catch (cause) {
    if (liveness.isCurrent(turn)) error.value = problem(cause);
  } finally {
    if (liveness.isCurrent(turn)) busy.value = false;
  }
  if (adopted && liveness.isCurrent(turn)) await execute();
}

async function changeStatus(): Promise<void> {
  const row = selected.value;
  if (!identity.value || !row || busy.value || pendingSave.value || !reason.value.trim()) return;
  const disable = row.status === "ACTIVE";
  if (!window.confirm(`${disable ? "停用" : "启用"} ${row.name}\n${reason.value.trim()}`)) return;
  draftSave(() => ({ path: `/${kind.value}/${row.id}/${disable ? "disable" : "enable"}`,
    method: "POST", version: row.version, body: { reason: reason.value.trim() } }));
  adoptSave();
  await execute();
}

async function move(delta: number): Promise<void> {
  selected.value = null; formOpen.value = false; page.value += delta; await load();
}
watch([identity, kind], () => {
  liveness.invalidate();
  rows.value = []; page.value = 1; total.value = 0; selected.value = null; formOpen.value = false;
  code.value = ""; name.value = ""; description.value = ""; extensions.value = [];
  suggestedUnit.value = ""; numberingPrefix.value = ""; reason.value = "";
  references.value = { parentCategory: "", defaultManufacturer: "", parentLocation: "", warehouse: "" };
  busy.value = false; resetSave(); error.value = ""; notice.value = ""; void load();
}, { immediate: true });
</script>

<template>
  <AppChrome>
    <p v-if="!identity" class="fams-notice" data-state="noaccess" role="alert"><strong>只有系统管理员可以维护资料目录。</strong></p>
    <template v-else>
      <p v-if="error" class="fams-notice" data-state="error" role="alert"><strong>{{ error }}</strong></p>
      <p v-if="notice" class="fams-notice" data-state="success" role="status"><strong>{{ notice }}</strong></p>
      <nav aria-label="目录类型" class="fams-inline">
        <button v-for="(label, value) in catalogNames" :key="value" class="fams-button" data-kind="secondary" data-size="sm"
          type="button" :disabled="controlsDisabled" @click="kind = value">{{ label }}</button>
      </nav>
      <section class="fams-panel fams-stack">
        <div class="fams-panel-head">
          <h2 class="fams-panel-title">{{ catalogNameText }}</h2>
          <div class="fams-panel-actions">
            <button class="fams-button" data-kind="secondary" data-size="sm" type="button" :disabled="controlsDisabled" @click="load">刷新目录</button>
            <button class="fams-button" data-kind="primary" data-size="sm" type="button" :disabled="controlsDisabled" @click="open(null)">{{ createLabel }}</button>
            <button v-if="pendingSave" class="fams-button" data-kind="secondary" data-size="sm" type="button" :disabled="retryDisabled" @click="execute">原请求重试保存</button>
          </div>
        </div>
        <div class="fams-table-scroll">
          <table class="fams-table"><thead><tr><th scope="col" data-column-kind="identity">编号</th><th scope="col">编码</th>
            <th scope="col">名称</th><th scope="col">关联资料</th><th scope="col">状态</th><th scope="col">版本</th>
            <th scope="col" data-column-kind="action">操作</th></tr></thead>
          <tbody><tr v-for="row in catalogRows" :key="row.id">
            <td data-column-kind="identity">{{ row.id }}</td><td>{{ row.code }}</td><td>{{ row.name }}</td>
            <td><p v-for="line in row.referenceLines" :key="line.field">{{ line.text }}</p></td>
            <td>{{ row.status }}</td><td>{{ row.version }}</td>
            <td data-column-kind="action"><button class="fams-button" data-kind="secondary" data-size="sm"
              type="button" :disabled="controlsDisabled" @click="open(row)">维护</button></td>
          </tr></tbody>
        </table>
        </div>
        <div class="fams-pagination">
          <span class="fams-pagination-summary">第 {{ page }} 页，共 {{ total }} 条</span>
          <div class="fams-pagination-actions">
            <button class="fams-button" data-kind="secondary" data-size="sm" type="button" :disabled="prevDisabled" @click="move(-1)">上一页</button>
            <button class="fams-button" data-kind="secondary" data-size="sm" type="button" :disabled="nextDisabled" @click="move(1)">下一页</button>
          </div>
        </div>
      </section>
      <section v-if="formOpen" class="fams-panel fams-stack" aria-label="维护目录资料">
        <div class="fams-panel-head"><h2 class="fams-panel-title">{{ formTitle }}</h2></div>
        <form @submit.prevent="save"><fieldset :disabled="controlsDisabled">
          <div class="fams-field-grid">
            <label class="fams-field"><span class="fams-field-label">编码</span><input class="fams-control" v-model="code" maxlength="64" required /></label>
            <label class="fams-field"><span class="fams-field-label">名称</span><input class="fams-control" v-model="name" maxlength="128" required /></label>
            <label class="fams-field fams-field-wide"><span class="fams-field-label">说明</span><textarea class="fams-control" v-model="description" maxlength="500" /></label>
            <label v-for="definition in activeReferences" :key="definition.field" class="fams-field">
              <span class="fams-field-label">{{ definition.label }}</span>
              <input class="fams-control" v-model="references[definition.field]" inputmode="numeric" />
            </label>
            <p v-if="referenceHintVisible" class="fams-field-hint fams-field-wide">关联编号留空保留原关系。</p>
            <template v-if="isLocations">
              <label class="fams-field"><span class="fams-field-label">位置类型</span><select class="fams-control" v-model="locationType">
                <option v-for="(label, value) in locationTypes" :key="value" :value="value">{{ label }}</option>
              </select></label>
            </template>
            <template v-if="isCategories">
              <label class="fams-field"><span class="fams-field-label">建议单位</span><input class="fams-control" v-model="suggestedUnit" maxlength="32" /></label>
              <label class="fams-field"><span class="fams-field-label">编号前缀</span><input class="fams-control" v-model="numberingPrefix" maxlength="32" /></label>
              <fieldset class="fams-field-wide fams-stack"><legend>扩展字段</legend>
                <div class="fams-panel-actions"><button class="fams-button" data-kind="secondary" data-size="sm" type="button"
                  :disabled="addExtensionDisabled" @click="extensions.push({ key: '', label: '', type: 'TEXT', required: false })">添加扩展字段</button></div>
                <div v-for="(field, index) in extensions" :key="index" class="fams-field-grid">
                  <label class="fams-field"><span class="fams-field-label">字段标识</span><input class="fams-control" v-model="field.key" maxlength="64" required /></label>
                  <label class="fams-field"><span class="fams-field-label">字段名称</span><input class="fams-control" v-model="field.label" maxlength="128" required /></label>
                  <label class="fams-field"><span class="fams-field-label">字段类型</span><select class="fams-control" v-model="field.type">
                    <option value="TEXT">文本</option><option value="NUMBER">数值</option>
                    <option value="DATE">日期</option><option value="BOOLEAN">是/否</option>
                  </select></label>
                  <label class="fams-selection"><input v-model="field.required" type="checkbox" />必填</label>
                  <button class="fams-button" data-kind="danger" data-size="sm" type="button" @click="extensions.splice(index, 1)">移除字段</button>
                </div>
              </fieldset>
            </template>
          </div>
          <div class="fams-form-actions"><button class="fams-button" data-kind="primary" type="submit">保存资料</button></div>
        </fieldset></form>
        <fieldset v-if="selected" :disabled="controlsDisabled">
          <label class="fams-field fams-field-wide"><span class="fams-field-label">状态变更原因</span><textarea class="fams-control" v-model="reason" maxlength="500" /></label>
          <div class="fams-form-actions"><button class="fams-button" type="button" :data-kind="statusActionKind"
            :disabled="statusActionDisabled" @click="changeStatus">{{ statusActionLabel }}</button></div>
        </fieldset>
      </section>
    </template>
  </AppChrome>
</template>
