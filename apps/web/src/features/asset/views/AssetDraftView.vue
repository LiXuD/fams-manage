<script setup lang="ts">
import { onMounted, ref, shallowRef } from "vue";
import { useRouter } from "vue-router";
import AppChrome from "@/components/AppChrome.vue";
import { activeReferences, createAsset, type AssetReference } from "@/features/asset/api";

const router = useRouter();
const busy = shallowRef(false);
const error = shallowRef("");
const name = shallowRef("");
const model = shallowRef("");
const unit = shallowRef("");
const categoryId = shallowRef("");
const organizationId = shallowRef("");
const warehouseId = shallowRef("");
const locationId = shallowRef("");
const categories = ref<AssetReference[]>([]);
const organizations = ref<AssetReference[]>([]);
const warehouses = ref<AssetReference[]>([]);
const locations = ref<AssetReference[]>([]);

function selectedReference(items: AssetReference[], id: string): AssetReference | undefined {
  return items.find((item) => item.id === id);
}

async function load(): Promise<void> {
  error.value = "";
  try {
    const [categoryPage, organizationPage, warehousePage, locationPage] = await Promise.all([
      activeReferences("/asset-categories"), activeReferences("/organizations"),
      activeReferences("/warehouses"), activeReferences("/locations"),
    ]);
    categories.value = categoryPage.items;
    organizations.value = organizationPage.items;
    warehouses.value = warehousePage.items;
    locations.value = locationPage.items;
  } catch (cause) { error.value = cause instanceof Error ? cause.message : "基础资料加载失败"; }
}

async function submit(): Promise<void> {
  if (!name.value.trim()) { error.value = "请填写资产名称"; return; }
  busy.value = true;
  error.value = "";
  try {
    const payload: Record<string, unknown> = { name: name.value.trim() };
    if (model.value.trim()) payload.model = model.value.trim();
    if (unit.value.trim()) payload.unit = unit.value.trim();
    const category = selectedReference(categories.value, categoryId.value);
    const organization = selectedReference(organizations.value, organizationId.value);
    const warehouse = selectedReference(warehouses.value, warehouseId.value);
    const location = selectedReference(locations.value, locationId.value);
    if (category) payload.category = { id: category.id, version: category.version };
    if (organization) payload.organization = { id: organization.id, version: organization.version };
    if (warehouse) payload.plannedInboundWarehouse = { id: warehouse.id, version: warehouse.version };
    if (location) payload.plannedInboundLocation = { id: location.id, version: location.version };
    const created = await createAsset(payload);
    await router.push(`/assets/${encodeURIComponent(created.id)}?saved=1`);
  } catch (cause) { error.value = cause instanceof Error ? cause.message : "保存失败"; }
  finally { busy.value = false; }
}

onMounted(load);
</script>

<template>
  <AppChrome>
    <template #actions>
      <router-link class="fams-button" data-kind="secondary" data-size="sm" to="/assets">返回台账列表</router-link>
    </template>

    <section v-if="error" class="fams-notice" data-state="error" role="alert"><strong>{{ error }}</strong></section>

    <section class="fams-panel fams-stack" aria-labelledby="create-title">
      <div class="fams-panel-head">
        <h2 id="create-title" class="fams-panel-title">单个建卡 · 保存草稿</h2>
      </div>
      <form class="fams-stack" @submit.prevent="submit">
        <div class="fams-field-grid">
          <div class="fams-field">
            <label for="create-name">资产名称<span class="fams-field-required">*</span></label>
            <input id="create-name" class="fams-control" v-model="name" maxlength="256" required />
          </div>
          <div class="fams-field">
            <label for="create-model">规格型号</label>
            <input id="create-model" class="fams-control" v-model="model" maxlength="128" />
          </div>
          <div class="fams-field">
            <label for="create-unit">单位</label>
            <input id="create-unit" class="fams-control" v-model="unit" maxlength="32" />
          </div>
          <div class="fams-field">
            <label for="create-category">分类</label>
            <select id="create-category" class="fams-control" v-model="categoryId">
              <option value="">稍后填写</option>
              <option v-for="item in categories" :key="item.id" :value="item.id">{{ item.name ?? item.code }}</option>
            </select>
          </div>
          <div class="fams-field">
            <label for="create-organization">所属组织</label>
            <select id="create-organization" class="fams-control" v-model="organizationId">
              <option value="">稍后填写</option>
              <option v-for="item in organizations" :key="item.id" :value="item.id">{{ item.name ?? item.code }}</option>
            </select>
          </div>
          <div class="fams-field">
            <label for="create-warehouse">计划仓库</label>
            <select id="create-warehouse" class="fams-control" v-model="warehouseId">
              <option value="">稍后填写</option>
              <option v-for="item in warehouses" :key="item.id" :value="item.id">{{ item.name ?? item.code }}</option>
            </select>
          </div>
          <div class="fams-field">
            <label for="create-location">计划位置</label>
            <select id="create-location" class="fams-control" v-model="locationId">
              <option value="">稍后填写</option>
              <option v-for="item in locations" :key="item.id" :value="item.id">{{ item.name ?? item.code }}</option>
            </select>
          </div>
        </div>
        <div class="fams-form-actions">
          <button class="fams-button" data-kind="primary" type="submit" :disabled="busy"
            :data-state="busy ? 'loading' : null">保存草稿</button>
        </div>
      </form>
    </section>
  </AppChrome>
</template>
