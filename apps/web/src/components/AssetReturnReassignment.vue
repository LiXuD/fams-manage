<script setup lang="ts">
import { loanLabels as labels } from "@/loan/api";
import { useReassignmentFlow } from "@/composables/useReassignmentFlow";

const props = defineProps<{ id: string; version: number }>();
const emit = defineEmits<{ saved: [] }>();
interface Item { id: string; version: number; status: string; assetVersion: number; lockVersion: number; assetCode: string }

const { snapshot, target, reason, evidence, checked, busy, error, retryable, inspect, submit } =
  useReassignmentFlow<Item>(props, {
    collection: "asset-returns",
    mapItems: (item) => ({ id: item.id, version: item.version, status: item.status, assetVersion: item.assetVersion, lockVersion: item.lockVersion }),
    onSaved: () => emit("saved"),
  });
</script>

<template>
  <section class="fams-card fams-stack">
    <div class="fams-panel-head">
      <div>
        <h3 class="fams-panel-title">受控转派整单处理权</h3>
        <p class="fams-panel-subtitle">核对每项实物阶段、去向、资产锁及未同步结果后，交给一名有效库管继续处理。</p>
      </div>
      <button class="fams-button" data-kind="secondary" data-size="sm" type="button" :disabled="busy" @click="inspect">加载 / 重新核对转派清单</button>
    </div>
    <p v-if="error" class="fams-notice" data-state="error" role="alert"><strong>{{ error }}</strong></p>
    <template v-if="snapshot">
      <p class="fams-panel-subtitle">当前处理账号 {{ snapshot.processorAccountId }} · 处理权版本 {{ snapshot.ownershipVersion }}</p>
      <ul class="fams-timeline">
        <li v-for="item in snapshot.items" :key="item.id">明细 {{ item.id }} · {{ item.assetCode }} · {{ labels[item.status] }}<small>锁版本 {{ item.lockVersion < 0 ? '已释放' : item.lockVersion }}</small></li>
      </ul>
      <div class="fams-field-grid">
        <label class="fams-field"><span class="fams-field-label">接任库管</span>
          <select class="fams-control" v-model="target" :disabled="busy || retryable"><option value="">请选择</option><option v-for="person in snapshot.operators" :key="person.id" :value="person.id">{{ person.name }}</option></select></label>
        <label class="fams-field"><span class="fams-field-label">转派原因</span>
          <textarea class="fams-control" v-model="reason" maxlength="1000" :disabled="busy || retryable" /></label>
        <label class="fams-field"><span class="fams-field-label">实物阶段与资产锁交接凭证</span>
          <textarea class="fams-control" v-model="evidence" maxlength="1000" :disabled="busy || retryable" /></label>
      </div>
      <label class="fams-selection"><input v-model="checked" type="checkbox" :disabled="busy || retryable" />已与处理人或交接记录核对未同步结果</label>
      <div class="fams-panel-actions">
        <button class="fams-button" data-kind="primary" type="button" :disabled="busy" @click="submit">{{ retryable ? '原请求重试转派' : '确认转派整单' }}</button>
      </div>
    </template>
  </section>
</template>
