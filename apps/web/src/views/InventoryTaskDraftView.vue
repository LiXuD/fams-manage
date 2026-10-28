<script setup lang="ts">
import { computed, onMounted, ref } from "vue";
import { useRouter } from "vue-router";
import AppChrome from "@/components/AppChrome.vue";
import { LoanApiError, loanMessage, loanRequest, type LoanMutation } from "@/loan/api";

type Range = { usingOrganizationId: string | null; owningOrganizationId: string | null; categoryId: string | null;
  warehouseId: string | null; locationId: string | null; lifecycle: string | null; exception: string | null; responsibleEmployeeId: string | null };
type Task = { id: string; name: string; version: number; starts_at: string; due_at: string; range_json?: Range };
type Choice = { id: string; name: string; status?: string };
const props = defineProps<{ id?: string }>();
const router = useRouter();
const name = ref(""); const start = ref(""); const due = ref("");
const emptyRange = (): Range => ({ usingOrganizationId: null, owningOrganizationId: null, categoryId: null, warehouseId: null,
  locationId: null, lifecycle: null, exception: null, responsibleEmployeeId: null });
const range = ref<Range>(emptyRange());
const catalogs = ref<Record<string, Choice[]>>({}); const version = ref(0);
const busy = ref(false); const error = ref(""); const pending = ref<LoanMutation | null>(null);
const locked = computed(() => busy.value || !!pending.value);
const lifecycleLabels: Record<string, string> = { IN_STOCK: "在库", IN_USE: "在用", BORROWED: "借出", UNDER_REPAIR: "维修中", FACTORY_REPAIR: "返厂维修", PENDING_SCRAP: "待报废", SCRAPPED: "已报废" };
function toLocal(value: string) {
  const date = new Date(value);
  return new Date(date.getTime() - date.getTimezoneOffset() * 60000).toISOString().slice(0, 16);
}
async function send(path: string, body: unknown, expectedVersion: number) {
  if (busy.value || pending.value && pending.value.path !== path) return;
  pending.value ??= { path, key: crypto.randomUUID(), version: expectedVersion, body: body === undefined ? undefined : JSON.parse(JSON.stringify(body)) as unknown };
  busy.value = true; error.value = "";
  try {
    const result = await loanRequest<Task>(pending.value.path, pending.value);
    pending.value = null;
    await router.push(`/inventory-tasks/${encodeURIComponent(result.id)}`);
  } catch (e) {
    error.value = loanMessage(e);
    if (e instanceof LoanApiError && e.status >= 400 && e.status < 500) pending.value = null;
  } finally { busy.value = false; }
}
function save() {
  const startsAt = new Date(start.value); const dueAt = new Date(due.value);
  if (!name.value.trim() || !Number.isFinite(startsAt.getTime()) || !Number.isFinite(dueAt.getTime()) || dueAt <= startsAt) {
    error.value = "请填写名称和有效的开始、截止时间。"; return;
  }
  const normalized = { ...range.value, responsibleEmployeeId: range.value.responsibleEmployeeId?.trim() || null };
  const body = { name: name.value.trim(), range: normalized, startsAt: startsAt.toISOString(), dueAt: dueAt.toISOString() };
  void send(props.id ? `/inventory-tasks/${props.id}/revision` : "/inventory-tasks", body, props.id ? version.value : 0);
}
onMounted(async () => {
  busy.value = true;
  try {
    if (props.id) {
      const result = await loanRequest<{ task: Task }>(`/inventory-tasks/${encodeURIComponent(props.id)}?page=1&pageSize=1`);
      version.value = result.task.version; name.value = result.task.name;
      start.value = toLocal(result.task.starts_at); due.value = toLocal(result.task.due_at);
      range.value = { ...emptyRange(), ...result.task.range_json };
    }
    for (const kind of ["organization", "category", "warehouse", "location", "employee"]) {
      const all: Choice[] = []; let p = 1; let rows: Choice[];
      do {
        const path = `/inventory-tasks/catalog/${kind}?page=${p++}&pageSize=200`;
        rows = (await loanRequest<{ items: Choice[] }>(path)).items; all.push(...rows);
      } while (rows.length === 200);
      catalogs.value[kind] = all;
    }
  } catch (e) { error.value = loanMessage(e); } finally { busy.value = false; }
});
</script>
<template>
  <AppChrome>
    <template #actions>
      <router-link class="fams-button" data-kind="secondary" data-size="sm" to="/inventory-tasks">返回任务列表</router-link>
    </template>
    <section v-if="error" class="fams-notice" data-state="error" role="alert"><strong>{{ error }}</strong></section>

    <form class="fams-panel fams-stack" @submit.prevent="save">
      <fieldset class="fams-stack" :disabled="locked">
        <div class="fams-field-grid">
          <label class="fams-field"><span class="fams-field-label">任务名称<span class="fams-field-required">*</span></span>
            <input class="fams-control" v-model="name" required maxlength="128" /></label>
          <label class="fams-field"><span class="fams-field-label">开始时间<span class="fams-field-required">*</span></span>
            <input class="fams-control" v-model="start" type="datetime-local" required /></label>
          <label class="fams-field"><span class="fams-field-label">截止时间<span class="fams-field-required">*</span></span>
            <input class="fams-control" v-model="due" type="datetime-local" required /></label>
          <label v-for="field in ([['usingOrganizationId','使用部门','organization'],['owningOrganizationId','所属组织','organization'],['categoryId','分类','category'],['warehouseId','仓库','warehouse'],['locationId','位置','location']] as const)" :key="field[0]" class="fams-field">
            <span class="fams-field-label">{{ field[1] }}</span>
            <select class="fams-control" v-model="range[field[0]]"><option :value="null">全部</option><option v-for="c in catalogs[field[2]]" :key="c.id" :value="c.id">{{ c.name }}{{ c.status === "DISABLED" ? "（已停用）" : "" }}</option></select>
          </label>
          <label class="fams-field"><span class="fams-field-label">生命周期</span>
            <select class="fams-control" v-model="range.lifecycle"><option :value="null">全部</option><option v-for="(text, value) in lifecycleLabels" :key="value" :value="value">{{ text }}</option></select></label>
          <label class="fams-field"><span class="fams-field-label">异常状态</span>
            <select class="fams-control" v-model="range.exception"><option :value="null">全部</option><option value="NORMAL">正常</option><option value="SEARCHING">查找中</option><option value="LOST">遗失</option></select></label>
          <label class="fams-field"><span class="fams-field-label">当前责任人</span>
            <select class="fams-control" v-model="range.responsibleEmployeeId"><option :value="null">全部</option><option v-for="c in catalogs.employee" :key="c.id" :value="c.id">{{ c.name }}{{ c.status === "DISABLED" ? "（已停用）" : "" }}</option></select></label>
        </div>
        <p class="fams-panel-subtitle">范围留空表示全部。保存草稿不会冻结集合；到任务详情页预览应盘数量后再发布。</p>
        <div class="fams-form-actions">
          <button class="fams-button" data-kind="primary" type="submit" :disabled="locked">保存草稿</button>
          <button v-if="pending" class="fams-button" data-kind="secondary" type="button" :disabled="busy"
            @click="send(pending.path, pending.body, pending.version)">重试原操作</button>
        </div>
      </fieldset>
    </form>
  </AppChrome>
</template>
