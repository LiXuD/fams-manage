import { createInventoryPendingPage } from "../../inventory-pending-pages.ts";
Page(createInventoryPendingPage(() => getApp().getServices()));
