import { createInventoryReadinessPage } from "../../inventory-readiness-pages.ts";
Page(createInventoryReadinessPage(() => getApp().getServices()));
