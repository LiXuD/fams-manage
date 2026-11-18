<script setup lang="ts">
import { computed, ref, watch } from "vue";
import AppChrome from "@/components/AppChrome.vue";
import { useSessionStore } from "@/auth/sessionStore";
import { masterDataRequest, MasterDataApiError, type MasterDataMutation } from "@/masterdata/api";

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
const session = useSessionStore();
const identity = computed(() => session.user?.roles.includes("SYSTEM_ADMIN") ? session.user.id : "");
const kind = ref<Catalog>("asset-categories");
const rows = ref<CatalogRow[]>([]);
const page = ref(1); const total = ref(0);
const selected = ref<CatalogRow | null>(null);
const formOpen = ref(false);
const code = ref(""); const name = ref(""); const description = ref("");
const suggestedUnit = ref(""); const numberingPrefix = ref("");
const locationType = ref("ROOM");
const references = ref<Record<ReferenceField, string>>({ parentCategory: "", defaultManufacturer: "", parentLocation: "", warehouse: "" });
const extensions = ref<ExtensionField[]>([]);
const reason = ref(""); const error = ref(""); const notice = ref("");
const busy = ref(false);
const pending = ref<MasterDataMutation | null>(null);
let generation = 0;

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
  if (!identity.value || busy.value || pending.value) return;
  const run = ++generation;
  busy.value = true; error.value = "";
  try {
    const result = await masterDataRequest<{ items: CatalogRow[]; total: number }>(
      `/${kind.value}?page=${page.value}&pageSize=20`);
    if (run !== generation) return;
    rows.value = result.items; total.value = result.total;
    if (selected.value) {
      const row = result.items.find(item => item.id === selected.value?.id);
      if (row) open(row);
      else { selected.value = null; formOpen.value = false; }
    }
  } catch (cause) {
    if (run === generation) error.value = problem(cause);
  } finally {
    if (run === generation) busy.value = false;
  }
}

async function execute(): Promise<void> {
  if (!identity.value || !pending.value || busy.value) return;
  const run = generation;
  const operation = pending.value;
  busy.value = true; error.value = "";
  try {
    const result = await masterDataRequest<CatalogRow>(operation.path, operation);
    if (run !== generation) return;
    pending.value = null; selected.value = null; formOpen.value = false;
    notice.value = `${result.name} 已保存，状态 ${result.status}，版本 ${result.version}。`;
    busy.value = false;
    await load();
  } catch (cause) {
    if (run !== generation) return;
    if (cause instanceof MasterDataApiError && cause.status >= 400 && cause.status < 500) pending.value = null;
    error.value = problem(cause);
  } finally {
    if (run === generation) busy.value = false;
  }
}

async function save(): Promise<void> {
  if (!identity.value || busy.value || pending.value || !code.value.trim() || !name.value.trim()) return;
  const row = selected.value;
  const run = generation;
  const body: Record<string, unknown> = { code: code.value.trim(), name: name.value.trim(), description: description.value };
  if (kind.value === "asset-categories") {
    body.suggestedUnit = suggestedUnit.value; body.numberingPrefix = numberingPrefix.value;
    body.extensionFieldTemplate = extensions.value.map(field => ({ ...field }));
  }
  if (kind.value === "locations") body.type = locationType.value;
  let operation: MasterDataMutation | null = null;
  busy.value = true; error.value = "";
  try {
    for (const definition of referenceDefinitions[kind.value]) {
      const id = references.value[definition.field];
      if (id && id !== row?.[definition.field]?.id) {
        if (!/^[1-9]\d*$/.test(id)) throw new MasterDataApiError(400, "CATALOG_REFERENCE_INVALID");
        const reference = await masterDataRequest<CatalogRow>(`/${definition.catalog}/${id}`);
        if (run !== generation) return;
        if (reference.status !== "ACTIVE") throw new MasterDataApiError(409, "CATALOG_REFERENCE_DISABLED");
        body[definition.field] = { id: reference.id, version: reference.version };
      }
    }
    if (!window.confirm(`${row ? "更新" : "新建"}${catalogNames[kind.value]} ${name.value.trim()}`)) return;
    operation = { path: `/${kind.value}${row ? "/" + row.id : ""}`, method: row ? "PATCH" : "POST",
      key: crypto.randomUUID(), version: row?.version ?? 0, body };
  } catch (cause) {
    if (run === generation) error.value = problem(cause);
  } finally {
    if (run === generation) busy.value = false;
  }
  if (operation && run === generation) { pending.value = operation; await execute(); }
}

async function changeStatus(): Promise<void> {
  const row = selected.value;
  if (!identity.value || !row || busy.value || pending.value || !reason.value.trim()) return;
  const disable = row.status === "ACTIVE";
  if (!window.confirm(`${disable ? "停用" : "启用"} ${row.name}\n${reason.value.trim()}`)) return;
  pending.value = { path: `/${kind.value}/${row.id}/${disable ? "disable" : "enable"}`, method: "POST",
    key: crypto.randomUUID(), version: row.version, body: { reason: reason.value.trim() } };
  await execute();
}

async function move(delta: number): Promise<void> {
  selected.value = null; formOpen.value = false; page.value += delta; await load();
}
watch([identity, kind], () => {
  generation++; rows.value = []; page.value = 1; total.value = 0; selected.value = null; formOpen.value = false;
  code.value = ""; name.value = ""; description.value = ""; extensions.value = [];
  suggestedUnit.value = ""; numberingPrefix.value = ""; reason.value = "";
  references.value = { parentCategory: "", defaultManufacturer: "", parentLocation: "", warehouse: "" };
  busy.value = false; pending.value = null; error.value = ""; notice.value = ""; void load();
}, { immediate: true });
</script>

<template>
  <AppChrome>
    <h1>资料目录</h1>
    <p v-if="!identity" role="alert">只有系统管理员可以维护资料目录。</p>
    <template v-else>
      <p v-if="error" role="alert">{{ error }}</p><p v-if="notice" role="status">{{ notice }}</p>
      <nav aria-label="目录类型">
        <button v-for="(label, value) in catalogNames" :key="value" :disabled="busy || !!pending" @click="kind = value">{{ label }}</button>
      </nav>
      <h2>{{ catalogNames[kind] }}</h2>
      <button :disabled="busy || !!pending" @click="load">刷新目录</button>
      <button :disabled="busy || !!pending" @click="open(null)">新建{{ catalogNames[kind] }}</button>
      <button v-if="pending" :disabled="busy" @click="execute">原请求重试保存</button>
      <table><thead><tr><th>编号</th><th>编码</th><th>名称</th><th>关联资料</th><th>状态</th><th>版本</th><th>操作</th></tr></thead>
        <tbody><tr v-for="row in rows" :key="row.id">
          <td>{{ row.id }}</td><td>{{ row.code }}</td><td>{{ row.name }}</td>
          <td><p v-for="definition in referenceDefinitions[kind]" :key="definition.field">
            {{ definition.label }} {{ row[definition.field]?.id ?? "未设置" }}
          </p></td><td>{{ row.status }}</td><td>{{ row.version }}</td>
          <td><button :disabled="busy || !!pending" @click="open(row)">维护</button></td>
        </tr></tbody>
      </table>
      <p>第 {{ page }} 页，共 {{ total }} 条</p>
      <button :disabled="page <= 1 || busy || !!pending" @click="move(-1)">上一页</button>
      <button :disabled="page * 20 >= total || busy || !!pending" @click="move(1)">下一页</button>
      <section v-if="formOpen" aria-label="维护目录资料">
        <h2>{{ selected ? "维护" : "新建" }}{{ catalogNames[kind] }}</h2>
        <form @submit.prevent="save"><fieldset :disabled="busy || !!pending">
          <label>编码<input v-model="code" maxlength="64" required /></label>
          <label>名称<input v-model="name" maxlength="128" required /></label>
          <label>说明<textarea v-model="description" maxlength="500" /></label>
          <label v-for="definition in referenceDefinitions[kind]" :key="definition.field">{{ definition.label }}
            <input v-model="references[definition.field]" inputmode="numeric" />
          </label>
          <p v-if="selected && referenceDefinitions[kind].length">关联编号留空保留原关系。</p>
          <template v-if="kind === 'locations'">
            <label for="location-type">位置类型</label><select id="location-type" v-model="locationType">
              <option v-for="(label, value) in locationTypes" :key="value" :value="value">{{ label }}</option>
            </select>
          </template>
          <template v-if="kind === 'asset-categories'">
            <label>建议单位<input v-model="suggestedUnit" maxlength="32" /></label>
            <label>编号前缀<input v-model="numberingPrefix" maxlength="32" /></label>
            <fieldset><legend>扩展字段</legend>
              <button type="button" :disabled="extensions.length >= 32"
                @click="extensions.push({ key: '', label: '', type: 'TEXT', required: false })">添加扩展字段</button>
              <div v-for="(field, index) in extensions" :key="index">
                <label>字段标识<input v-model="field.key" maxlength="64" required /></label>
                <label>字段名称<input v-model="field.label" maxlength="128" required /></label>
                <label :for="'catalog-field-type-' + index">字段类型</label><select :id="'catalog-field-type-' + index" v-model="field.type">
                  <option value="TEXT">文本</option><option value="NUMBER">数值</option>
                  <option value="DATE">日期</option><option value="BOOLEAN">是/否</option>
                </select>
                <label><input v-model="field.required" type="checkbox" />必填</label>
                <button type="button" @click="extensions.splice(index, 1)">移除字段</button>
              </div>
            </fieldset>
          </template>
          <button type="submit">保存资料</button>
        </fieldset></form>
        <fieldset v-if="selected" :disabled="busy || !!pending">
          <label>状态变更原因<textarea v-model="reason" maxlength="500" /></label>
          <button :disabled="!reason.trim()" @click="changeStatus">{{ selected.status === "ACTIVE" ? "停用" : "启用" }}</button>
        </fieldset>
      </section>
    </template>
  </AppChrome>
</template>
