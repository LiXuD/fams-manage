import { createRouter, createWebHistory } from "vue-router";
import { useSessionStore } from "@/auth/sessionStore";
import HomeView from "@/views/HomeView.vue";
import LoginView from "@/views/LoginView.vue";
import PasswordChangeView from "@/views/PasswordChangeView.vue";
import AssetLedgerView from "@/views/AssetLedgerView.vue";
import AssetImportView from "@/views/AssetImportView.vue";
import AssetImportReviewView from "@/views/AssetImportReviewView.vue";
import AssetInitializationView from "@/views/AssetInitializationView.vue";
import InboundView from "@/views/InboundView.vue";
import RequisitionView from "@/views/RequisitionView.vue";
import LoanView from "@/views/LoanView.vue";
import TransferView from "@/views/TransferView.vue";

export const router = createRouter({
  history: createWebHistory(),
  routes: [
    { path: "/", name: "home", component: HomeView, meta: { requiresAuth: true } },
    { path: "/login", name: "login", component: LoginView },
    { path: "/password/change", name: "password-change", component: PasswordChangeView, meta: { requiresAuth: true } },
    { path: "/assets", name: "assets", component: AssetLedgerView, meta: { requiresAuth: true } },
    { path: "/asset-imports", name: "asset-import", component: AssetImportView, meta: { requiresAuth: true } },
    { path: "/asset-import-reviews", name: "asset-import-review", component: AssetImportReviewView, meta: { requiresAuth: true } },
    { path: "/asset-initialization", name: "asset-initialization", component: AssetInitializationView, meta: { requiresAuth: true } },
    { path: "/inbound", name: "inbound", component: InboundView, meta: { requiresAuth: true } },
    { path: "/requisitions", name: "requisitions", component: RequisitionView, meta: { requiresAuth: true } },
    { path: "/loans", name: "loans", component: LoanView, meta: { requiresAuth: true } },
    { path: "/transfers", name: "transfers", component: TransferView, meta: { requiresAuth: true } },
  ],
});

router.beforeEach(async (to) => {
  const session = useSessionStore();
  if (session.state === "booting") {
    await session.restore();
  }
  if (!session.isAuthenticated && to.meta.requiresAuth) {
    return { name: "login" };
  }
  if (session.state === "password-change-required" && to.name !== "password-change") {
    return { name: "password-change" };
  }
  if (session.state === "authenticated" && (to.name === "login" || to.name === "password-change")) {
    return { name: "home" };
  }
  return true;
});
