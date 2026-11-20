<script setup lang="ts">
import { computed, ref, shallowRef, watch } from "vue";
import AppChrome from "@/components/AppChrome.vue";
import { useSessionStore } from "@/auth/sessionStore";
import { MasterDataApiError, masterDataRequest } from "@/features/masterdata/api";
import { useRequestLiveness } from "@/composables/useRequestLiveness";

interface OperationLogEntry {
  source: "BUSINESS" | "SECURITY";
  eventId: string;
  eventType: string;
  requestId: string;
  occurredAt: string;
  detail: Record<string, unknown>;
  actorAccountId?: string;
  actorRole?: string;
  resourceType?: string;
  resourceId?: string;
}
interface OperationLogPage { page: number; pageSize: number; total: number; items: OperationLogEntry[] }

const sourceLabels = { BUSINESS: "业务审计", SECURITY: "安全审计" } as const;
const session = useSessionStore();
const identity = computed(() => {
  const roles = session.user?.roles ?? [];
  return roles.includes("SYSTEM_ADMIN") || roles.includes("AUDIT_READ_ONLY") ? session.user!.id : "";
});
const items = ref<OperationLogEntry[]>([]);
const selected = shallowRef<OperationLogEntry | null>(null);
const page = shallowRef(1);
const total = shallowRef(0);
const source = shallowRef("");
const actor = shallowRef("");
const occurredFrom = shallowRef("");
const occurredTo = shallowRef("");
const ready = shallowRef(false);
const busy = shallowRef(false);
const error = shallowRef("");
const liveness = useRequestLiveness(() => [identity.value]);

const prevDisabled = computed(() => page.value <= 1 || busy.value);
const nextDisabled = computed(() => page.value * 20 >= total.value || busy.value);
const selectedDetail = computed(() => selected.value ? JSON.stringify(selected.value.detail, null, 2) : "");

function problem(cause: unknown): string {
  if (cause instanceof MasterDataApiError) {
    if (cause.status === 401) return "登录已失效，请重新登录。";
    if (cause.status === 403) return "当前账号没有系统日志读取授权，请联系系统管理员配置审计授权。";
    if (cause.status === 400) return "请核对筛选条件后重新查询。";
  }
  return "日志加载未完成，请稍后重试。";
}

function instant(value: string): string {
  const parsed = new Date(value);
  if (!Number.isFinite(parsed.getTime())) throw new Error("时间筛选须为有效日期时间。");
  return parsed.toISOString();
}

function query(): string {
  let path = `/operation-logs?page=${page.value}&pageSize=20`;
  if (source.value) path += `&source=${source.value}`;
  if (actor.value.trim()) path += `&actorAccountId=${encodeURIComponent(actor.value.trim())}`;
  if (occurredFrom.value) path += `&occurredFrom=${encodeURIComponent(instant(occurredFrom.value))}`;
  if (occurredTo.value) path += `&occurredTo=${encodeURIComponent(instant(occurredTo.value))}`;
  return path;
}

async function load(): Promise<void> {
  if (!identity.value || busy.value) return;
  const turn = liveness.begin();
  busy.value = true; error.value = "";
  try {
    const path = query();
    const result = await masterDataRequest<OperationLogPage>(path);
    if (!liveness.isCurrent(turn)) return;
    items.value = result.items; total.value = result.total; selected.value = null;
    ready.value = true;
  } catch (cause) {
    if (liveness.isCurrent(turn)) {
      error.value = cause instanceof Error && !(cause instanceof MasterDataApiError)
        ? cause.message : problem(cause);
    }
  } finally {
    if (liveness.isCurrent(turn)) busy.value = false;
  }
}

async function search(): Promise<void> {
  page.value = 1;
  await load();
}

async function move(delta: number): Promise<void> {
  page.value += delta;
  await load();
}

watch(identity, () => {
  liveness.invalidate();
  items.value = []; selected.value = null; page.value = 1; total.value = 0;
  source.value = ""; actor.value = ""; occurredFrom.value = ""; occurredTo.value = "";
  ready.value = false; busy.value = false; error.value = "";
  void load();
}, { immediate: true });
</script>

<template>
  <AppChrome>
    <p v-if="!identity" class="fams-notice" data-state="noaccess" role="alert"><strong>只有系统管理员或获得授权的审计只读账号可以查看系统操作日志。</strong></p>
    <template v-else>
      <p v-if="error" class="fams-notice" data-state="error" role="alert"><strong>{{ error }}</strong></p>
      <section class="fams-panel fams-stack" aria-label="日志筛选">
        <div class="fams-panel-head"><h2 class="fams-panel-title">只读查询</h2></div>
        <p>日志合并业务审计与安全审计，按时间倒序分页；详情中的手机号等敏感值已脱敏，凭证材料永不返回。每次查询服务端都重新校验当前授权。</p>
        <form @submit.prevent="search">
          <fieldset class="fams-stack" :disabled="busy">
            <legend>筛选条件</legend>
            <div class="fams-field-grid">
              <label class="fams-field"><span class="fams-field-label">来源</span>
                <select id="log-source" class="fams-control" v-model="source">
                  <option value="">全部</option>
                  <option value="BUSINESS">业务审计</option>
                  <option value="SECURITY">安全审计</option>
                </select>
              </label>
              <label class="fams-field"><span class="fams-field-label">操作账号 ID</span>
                <input class="fams-control" v-model="actor" inputmode="numeric" placeholder="可留空" />
              </label>
              <label class="fams-field"><span class="fams-field-label">开始时间</span>
                <input class="fams-control" v-model="occurredFrom" type="datetime-local" />
              </label>
              <label class="fams-field"><span class="fams-field-label">结束时间</span>
                <input class="fams-control" v-model="occurredTo" type="datetime-local" />
              </label>
            </div>
            <div class="fams-form-actions">
              <button class="fams-button" data-kind="primary" type="submit">查询</button>
              <button class="fams-button" data-kind="secondary" type="button" :disabled="busy" @click="load">刷新本页</button>
            </div>
          </fieldset>
        </form>
      </section>
      <section v-if="ready" class="fams-panel fams-stack" aria-label="日志列表">
        <div class="fams-panel-head"><h2 class="fams-panel-title">日志记录（共 {{ total }} 条）</h2></div>
        <div class="fams-table-scroll"><table class="fams-table">
          <thead><tr><th scope="col">时间</th><th scope="col">来源</th><th scope="col">事件类型</th><th scope="col">操作账号</th><th scope="col">资源</th><th scope="col" data-column-kind="action">详情</th></tr></thead>
          <tbody><tr v-for="row in items" :key="`${row.source}-${row.eventId}`">
            <td>{{ row.occurredAt }}</td>
            <td>{{ sourceLabels[row.source] }}</td>
            <td>{{ row.eventType }}</td>
            <td>{{ row.actorAccountId ?? "—" }}<template v-if="row.actorRole"> · {{ row.actorRole }}</template></td>
            <td>{{ row.resourceType ? `${row.resourceType} ${row.resourceId ?? ""}` : "—" }}</td>
            <td data-column-kind="action"><button class="fams-button" data-kind="link" data-size="sm" type="button" @click="selected = row">查看详情</button></td>
          </tr></tbody>
        </table></div>
        <p v-if="!items.length" class="fams-notice" data-state="empty">当前筛选条件下没有日志记录。</p>
        <div class="fams-pagination">
          <div class="fams-pagination-actions">
            <button class="fams-button" data-kind="secondary" data-size="sm" type="button" :disabled="prevDisabled" @click="move(-1)">上一页</button>
            <button class="fams-button" data-kind="secondary" data-size="sm" type="button" :disabled="nextDisabled" @click="move(1)">下一页</button>
          </div>
        </div>
        <dl v-if="selected" class="fams-facts">
          <dt>请求 ID</dt><dd>{{ selected.requestId }}</dd>
          <dt>事件 ID</dt><dd>{{ selected.source }}-{{ selected.eventId }}</dd>
          <dt>脱敏详情</dt><dd><pre>{{ selectedDetail }}</pre></dd>
        </dl>
      </section>
    </template>
  </AppChrome>
</template>
