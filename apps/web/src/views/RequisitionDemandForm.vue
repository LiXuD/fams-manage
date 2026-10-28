<script setup lang="ts">
import { ref } from "vue";
import type { RequisitionChoice, RequisitionDemand, RequisitionDemandInput } from "../../../../packages/api-types/src/requisition";

const props = defineProps<{
  mode: "new" | "revise" | "change";
  catalogs: Record<string, RequisitionChoice[]>;
  canShared: boolean;
  selfEmployeeId: string;
  selfOrganizationId: string;
  demands: RequisitionDemand[] | null;
  requestType?: "REQUISITION" | "BORROW";
  expectedReturnDate?: string | null;
  disabled: boolean;
}>();
const emit = defineEmits<{ submit: [{ demands: RequisitionDemandInput[]; requestType: "REQUISITION" | "BORROW";
  expectedReturnDate: string | null }] }>();

const form = ref<RequisitionDemandInput[]>([]);
const requestType = ref<"REQUISITION" | "BORROW">(props.mode === "new" ? "REQUISITION" : props.requestType ?? "REQUISITION");
const expectedReturnDate = ref(props.mode === "new" ? "" : props.expectedReturnDate ?? "");

function newDemand(): RequisitionDemandInput {
  const reference = (kind: string, preferred?: string) => {
    const value = props.catalogs[kind]?.find(item => item.id === preferred) ?? props.catalogs[kind]?.[0];
    return { id: value?.id ?? "", version: value?.version ?? 0 };
  };
  return { category: reference("category"), specification: "", purpose: "", expectedDate: new Date().toISOString().slice(0, 10),
    custodyType: "PERSONAL", organization: reference("organization", props.selfOrganizationId), employee: reference("employee", props.selfEmployeeId),
    location: reference("location"), quantity: 1, preferredBrand: "", preferredModel: "" };
}
function setReference(d: RequisitionDemandInput, field: "category" | "organization" | "employee" | "location", event: Event) {
  const id = (event.target as HTMLSelectElement).value;
  const choice = props.catalogs[field]?.find(item => item.id === id);
  if (choice) {
    d[field] = { id: choice.id, version: choice.version };
    if (field === "organization") {
      const employee = props.catalogs.employee?.find(item => item.organizationId === choice.id);
      d.employee = { id: employee?.id ?? "", version: employee?.version ?? 0 };
    }
  }
}
form.value = props.mode === "new" ? [newDemand()] : (props.demands ?? []).map(d => ({
  category: { id: d.categoryId, version: d.categoryVersionSnapshot }, specification: d.specification,
  preferredBrand: d.preferredBrand ?? "", preferredModel: d.preferredModel ?? "", purpose: d.purpose,
  expectedDate: d.expectedDate, custodyType: d.custodyType,
  organization: { id: d.targetOrganizationId, version: d.organizationVersionSnapshot },
  employee: { id: d.targetEmployeeId ?? d.targetDepartmentEmployeeId!, version: d.employeeVersionSnapshot },
  location: { id: d.targetLocationId, version: d.locationVersionSnapshot }, quantity: d.requestedQuantity,
}));
</script>

<template>
  <form class="fams-panel fams-stack" aria-labelledby="demand-form-title" @submit.prevent="emit('submit', { demands: form.map(d => ({ ...d })), requestType, expectedReturnDate: requestType === 'BORROW' ? expectedReturnDate : null })">
    <div class="fams-panel-head">
      <h2 id="demand-form-title" class="fams-panel-title">{{ mode === 'new' ? '新建数量申请' : mode === 'change' ? '提请批准范围变更' : '修改草稿' }}</h2>
    </div>
    <div class="fams-field-grid">
      <label class="fams-field">
        <span class="fams-field-label">申请类型</span>
        <select class="fams-control" v-model="requestType" :disabled="disabled || mode === 'change'"
          @change="requestType === 'BORROW' && form.forEach(d => d.custodyType = 'PERSONAL')">
          <option value="REQUISITION">领用</option><option value="BORROW">借用</option></select>
      </label>
      <label v-if="requestType === 'BORROW'" class="fams-field">
        <span class="fams-field-label">预计归还日<span class="fams-field-required">*</span></span>
        <input class="fams-control" v-model="expectedReturnDate" type="date" required :disabled="disabled || mode === 'change'" />
      </label>
    </div>

    <fieldset v-for="(d, index) in form" :key="index" :disabled="disabled" class="fams-card fams-stack">
      <legend class="fams-panel-title">需求 {{ index + 1 }}</legend>
      <div class="fams-field-grid">
        <label class="fams-field"><span class="fams-field-label">分类</span>
          <select class="fams-control" :value="d.category.id" @change="setReference(d, 'category', $event)">
            <option v-for="c in catalogs.category" :key="c.id" :value="c.id">{{ c.name }}</option></select></label>
        <label class="fams-field"><span class="fams-field-label">规格<span class="fams-field-required">*</span></span>
          <input class="fams-control" v-model="d.specification" required maxlength="1000" /></label>
        <label class="fams-field"><span class="fams-field-label">数量<span class="fams-field-required">*</span></span>
          <input class="fams-control" v-model.number="d.quantity" type="number" min="1" max="10000" required /></label>
        <label class="fams-field"><span class="fams-field-label">用途<span class="fams-field-required">*</span></span>
          <input class="fams-control" v-model="d.purpose" required maxlength="1000" /></label>
        <label class="fams-field"><span class="fams-field-label">期望日期<span class="fams-field-required">*</span></span>
          <input class="fams-control" v-model="d.expectedDate" type="date" required /></label>
        <label class="fams-field"><span class="fams-field-label">偏好品牌</span>
          <input class="fams-control" v-model="d.preferredBrand" maxlength="128" /></label>
        <label class="fams-field"><span class="fams-field-label">偏好型号</span>
          <input class="fams-control" v-model="d.preferredModel" maxlength="128" /></label>
        <label class="fams-field"><span class="fams-field-label">使用方式</span>
          <select class="fams-control" v-model="d.custodyType"><option value="PERSONAL">个人使用</option><option v-if="canShared && requestType !== 'BORROW'" value="SHARED">部门公用</option></select></label>
        <label class="fams-field"><span class="fams-field-label">使用组织</span>
          <select class="fams-control" :value="d.organization.id" @change="setReference(d, 'organization', $event)">
            <option v-for="c in catalogs.organization" :key="c.id" :value="c.id">{{ c.name }}</option></select></label>
        <label class="fams-field"><span class="fams-field-label">{{ d.custodyType === 'PERSONAL' ? '实际使用人' : '部门责任人' }}</span>
          <select class="fams-control" :value="d.employee.id" @change="setReference(d, 'employee', $event)">
            <option v-for="c in catalogs.employee?.filter(c => c.organizationId === d.organization.id)" :key="c.id" :value="c.id">{{ c.name }}</option></select></label>
        <label class="fams-field"><span class="fams-field-label">固定位置</span>
          <select class="fams-control" :value="d.location.id" @change="setReference(d, 'location', $event)">
            <option v-for="c in catalogs.location" :key="c.id" :value="c.id">{{ c.name }}</option></select></label>
      </div>
      <div v-if="form.length > 1 && mode !== 'change'" class="fams-panel-actions">
        <button class="fams-button" data-kind="danger" type="button" @click="form.splice(index, 1)">移除此需求</button>
      </div>
    </fieldset>

    <div class="fams-form-actions">
      <button v-if="mode !== 'change' && form.length < 50" class="fams-button" data-kind="secondary" type="button"
        :disabled="disabled" @click="form.push(newDemand())">增加需求</button>
      <button class="fams-button" data-kind="primary" type="submit" :disabled="disabled">{{ mode === 'change' ? '提请变更确认' : '保存草稿' }}</button>
    </div>
    <p class="fams-field-hint">偏好不代表库存承诺。已履行和待交接数量不会被变更回退。</p>
  </form>
</template>
