<script setup lang="ts">
import { computed, ref, shallowRef } from "vue";
import { useRequestLiveness } from "@/composables/useRequestLiveness";
import { reportRequest } from "@/features/report/api";

type Dimension = "organizationId" | "categoryId" | "custody" | "custodyComplete";
interface Group { id: string; name: string | null; count: number }
interface Distribution {
  organizations: Group[];
  categories: Group[];
  custody: { custodyType: "PERSONAL" | "SHARED"; count: number }[];
  custodyQuality?: { complete: number; effective: number; excluded: number };
}
interface GroupRow extends Group { maxCount: number; nameText: string }
interface CustodyRow { custodyType: string; count: number; label: string }
const custodyLabels = { PERSONAL: "个人使用及借出", SHARED: "部门公用" };
const emit = defineEmits<{ select: [dimension: Dimension, id: string] }>();
const distribution = ref<Distribution>();
const busy = shallowRef(false);
const error = shallowRef("");
const hasNoAssets = computed(() => !distribution.value?.organizations.length);
const organizationRows = computed<GroupRow[]>(() => (distribution.value?.organizations ?? []).map((group) => ({
  ...group, maxCount: distribution.value!.organizations[0].count, nameText: group.name ?? "无使用部门",
})));
const categoryRows = computed<GroupRow[]>(() => (distribution.value?.categories ?? []).map((group) => ({
  ...group, maxCount: distribution.value!.categories[0].count, nameText: group.name ?? "未分类",
})));
const custodyRows = computed<CustodyRow[]>(() => (distribution.value?.custody ?? []).map((group) => ({
  ...group, label: custodyLabels[group.custodyType],
})));
const custodyQuality = computed(() => distribution.value?.custodyQuality);
const custodyQualityRate = computed(() => {
  const quality = distribution.value?.custodyQuality;
  if (!quality) return "";
  return quality.effective
    ? (quality.complete * 100 / quality.effective).toFixed(2) + "%"
    : "暂无有效资产";
});

const liveness = useRequestLiveness();

async function load() {
  const turn = liveness.begin();
  busy.value = true;
  error.value = "";
  distribution.value = undefined;
  try {
    const fetched = await reportRequest<Distribution>("/operations-reports/asset-distribution");
    if (!liveness.isCurrent(turn)) return;
    distribution.value = fetched;
  } catch {
    if (liveness.isCurrent(turn)) {
      error.value = "无法读取资产分布。请检查权限后重试。";
    }
  } finally {
    if (liveness.isCurrent(turn)) busy.value = false;
  }
}
</script>

<template>
  <section class="fams-card fams-stack" aria-label="资产分布">
    <div class="fams-panel-head">
      <h3 class="fams-panel-title">资产分布</h3>
    </div>
    <div class="fams-inline">
      <button class="fams-button" data-kind="secondary" data-size="sm" type="button" :disabled="busy" @click="load">查询资产分布</button>
    </div>
    <p v-if="error" class="fams-notice" data-state="error" role="alert"><strong>{{ error }}</strong></p>
    <p v-if="busy" role="status">正在查询</p>
    <template v-if="distribution">
      <section v-if="custodyQuality" aria-label="保管信息完整率" class="fams-stack">
        <h4>保管信息完整率</h4>
        <p>{{ custodyQualityRate }} · 完整 {{ custodyQuality.complete }} / 有效 {{ custodyQuality.effective }} · 排除 {{ custodyQuality.excluded }} 件</p>
        <button class="fams-button" data-kind="secondary" data-size="sm" type="button" @click="emit('select', 'custodyComplete', 'false')">查看保管信息不完整资产</button>
      </section>
      <p v-if="hasNoAssets">当前没有有效资产。</p>
      <template v-else>
        <h4>按当前使用部门</h4>
        <ul class="fams-timeline">
          <li v-for="group in organizationRows" :key="group.id">
            <button class="fams-button" data-kind="secondary" data-size="sm" type="button" @click="emit('select', 'organizationId', group.id)">
              <meter aria-hidden="true" :max="group.maxCount" :value="group.count"></meter>
              {{ group.nameText }} · {{ group.count }} 件
            </button>
          </li>
        </ul>
        <h4>按资产分类</h4>
        <ul class="fams-timeline">
          <li v-for="group in categoryRows" :key="group.id">
            <button class="fams-button" data-kind="secondary" data-size="sm" type="button" @click="emit('select', 'categoryId', group.id)">
              <meter aria-hidden="true" :max="group.maxCount" :value="group.count"></meter>
              {{ group.nameText }} · {{ group.count }} 件
            </button>
          </li>
        </ul>
        <h4>按保管方式</h4>
        <ul v-if="custodyRows.length" class="fams-timeline">
          <li v-for="group in custodyRows" :key="group.custodyType">
            <button class="fams-button" data-kind="secondary" data-size="sm" type="button" @click="emit('select', 'custody', group.custodyType)">
              {{ group.label }} · {{ group.count }} 件
            </button>
          </li>
        </ul>
        <p v-else>当前没有个人保管或部门公用资产。</p>
      </template>
    </template>
  </section>
</template>
