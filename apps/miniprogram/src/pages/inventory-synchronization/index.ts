import { createInventorySynchronizationPage } from "../../inventory-synchronization-pages.ts";
Page(createInventorySynchronizationPage(() => getApp().getServices()));
