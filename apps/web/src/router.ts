import { createRouter, createWebHistory } from "vue-router";
import { useSessionStore } from "@/auth/sessionStore";
import HomeView from "@/features/auth/views/HomeView.vue";
import LoginView from "@/features/auth/views/LoginView.vue";
import PasswordChangeView from "@/features/auth/views/PasswordChangeView.vue";

// 入口三页保持同步加载，其余视图按路由切分，避免 35 个视图进首屏 chunk。
const AssetLedgerView = () => import("@/features/asset/views/AssetLedgerView.vue");
const AssetDraftView = () => import("@/features/asset/views/AssetDraftView.vue");
const AssetDetailView = () => import("@/features/asset/views/AssetDetailView.vue");
const AssetImportView = () => import("@/features/asset-import/views/AssetImportView.vue");
const AssetImportReviewView = () => import("@/features/asset-import/views/AssetImportReviewView.vue");
const AssetInitializationView = () => import("@/features/asset-import/views/AssetInitializationView.vue");
const InboundView = () => import("@/features/inbound/views/InboundView.vue");
const InboundDraftView = () => import("@/features/inbound/views/InboundDraftView.vue");
const InboundDetailView = () => import("@/features/inbound/views/InboundDetailView.vue");
const RequisitionView = () => import("@/features/requisition/views/RequisitionView.vue");
const RequisitionDraftView = () => import("@/features/requisition/views/RequisitionDraftView.vue");
const RequisitionDetailView = () => import("@/features/requisition/views/RequisitionDetailView.vue");
const LoanView = () => import("@/features/loan/views/LoanView.vue");
const LoanDetailView = () => import("@/features/loan/views/LoanDetailView.vue");
const AssetReturnView = () => import("@/features/asset-return/views/AssetReturnView.vue");
const AssetReturnDraftView = () => import("@/features/asset-return/views/AssetReturnDraftView.vue");
const AssetReturnDetailView = () => import("@/features/asset-return/views/AssetReturnDetailView.vue");
const TransferView = () => import("@/features/transfer/views/TransferView.vue");
const TransferDraftView = () => import("@/features/transfer/views/TransferDraftView.vue");
const TransferDetailView = () => import("@/features/transfer/views/TransferDetailView.vue");

const ProxyConfirmationView = () => import("@/features/exception/views/ProxyConfirmationView.vue");
const BusinessResultNoticeView = () => import("@/features/exception/views/BusinessResultNoticeView.vue");
const BusinessResultReviewView = () => import("@/features/exception/views/BusinessResultReviewView.vue");
const MessageCenterView = () => import("@/features/reminder/views/MessageCenterView.vue");

const ItemTargetChangeView = () => import("@/features/requisition/views/ItemTargetChangeView.vue");
const TransferTargetChangeView = () => import("@/features/transfer/views/TransferTargetChangeView.vue");

const InventoryTaskView = () => import("@/features/inventory/views/InventoryTaskView.vue");
const InventoryTaskDraftView = () => import("@/features/inventory/views/InventoryTaskDraftView.vue");
const InventoryTaskDetailView = () => import("@/features/inventory/views/InventoryTaskDetailView.vue");
const RepairView = () => import("@/features/repair/views/RepairView.vue");
const RepairDraftView = () => import("@/features/repair/views/RepairDraftView.vue");
const RepairDetailView = () => import("@/features/repair/views/RepairDetailView.vue");
const RepairAssistanceView = () => import("@/features/repair/views/RepairAssistanceView.vue");
const LossView = () => import("@/features/loss/views/LossView.vue");
const ScrapView = () => import("@/features/scrap/views/ScrapView.vue");

declare module "vue-router" {
  interface RouteMeta {
    requiresAuth?: boolean;
    /** 页头分组，同时用于面包屑；缺省表示该页不使用外壳页头。 */
    uiGroup?: string;
    uiTitle?: string;
    uiDesc?: string;
  }
}

export const router = createRouter({
  history: createWebHistory(),
  routes: [
    { path: "/repair-assistance/:id?", name: "repair-assistance", component: RepairAssistanceView, meta: { requiresAuth: true, uiGroup: "业务流转", uiTitle: "我协助的报修", uiDesc: "只显示明确邀请的必要资料。判断和建议保留历史；实际维修、返厂或报废由相应合法业务办理。" } },
    { path: "/repairs", name: "repairs", component: RepairView, meta: { requiresAuth: true, uiGroup: "业务流转", uiTitle: "报修与维修", uiDesc: "为本人责任资产或本部门公用资产报修。管理员批准处理范围后，由唯一处理库管核实实物并办理。" } },
    { path: "/repairs/new", name: "repair-new", component: RepairDraftView, meta: { requiresAuth: true, uiGroup: "业务流转", uiTitle: "新建报修", uiDesc: "选择可报修资产并填写实际故障、影响程度和发生时间。提交后到报修详情等待管理员决定。" } },
    { path: "/repairs/:id", name: "repair-detail", component: RepairDetailView, meta: { requiresAuth: true, uiGroup: "业务流转", uiTitle: "报修详情", uiDesc: "决定、领取、原地维修、返厂、返还与协作只作用于本报修单。实物证据和历史保留在原记录上。" } },
    { path: "/loss-reports/:id?", name: "loss-reports", component: LossView, meta: { requiresAuth: true, uiGroup: "业务流转", uiTitle: "报失与查找", uiDesc: "报告找不到只冻结流转；批准核实后，由处理库管记录实际查找。原责任和借用继续保留。" } },
    { path: "/scrap-requests/:id?", name: "scrap-requests", component: ScrapView, meta: { requiresAuth: true, uiGroup: "业务流转", uiTitle: "报废与处置" } },
    { path: "/inventory-tasks", name: "inventory-tasks", component: InventoryTaskView, meta: { requiresAuth: true, uiGroup: "业务流转", uiTitle: "盘点任务", uiDesc: "发布时冻结应盘集合与责任快照。盘点不直接改变资产责任；执行人只查看自己的应盘项。" } },
    { path: "/inventory-tasks/new", name: "inventory-task-new", component: InventoryTaskDraftView, props: true, meta: { requiresAuth: true, uiGroup: "业务流转", uiTitle: "新建盘点草稿", uiDesc: "先划定盘点范围并保存草稿。到任务详情页预览应盘数量后再发布冻结。" } },
    { path: "/inventory-tasks/:id/edit", name: "inventory-task-edit", component: InventoryTaskDraftView, props: true, meta: { requiresAuth: true, uiGroup: "业务流转", uiTitle: "修改盘点草稿", uiDesc: "草稿发布前可调整范围与时间。已发布任务保留原快照，请新建任务。" } },
    { path: "/inventory-tasks/:id", name: "inventory-task-detail", component: InventoryTaskDetailView, props: true, meta: { requiresAuth: true, uiGroup: "业务流转", uiTitle: "盘点任务详情", uiDesc: "预览、发布、分配与复核只作用于本任务。已冻结快照不随资产流转改写。" } },
    { path: "/transfer-target-changes", name: "transfer-target-changes", component: TransferTargetChangeView, meta: { requiresAuth: true, uiGroup: "异常与核查", uiTitle: "调拨目标变更", uiDesc: "仅修改未完成明细的批准目标。原责任、已交出地点及物理锁保持，新目标仍按原业务实际接收或现场收妥。" } },
    { path: "/requisition-target-changes", name: "requisition-target-changes", component: ItemTargetChangeView, meta: { requiresAuth: true, uiGroup: "异常与核查", uiTitle: "领用与借用目标变更", uiDesc: "仅变更未完成明细。已交出的实物保持原交出记录和占用锁，批准不会提前转移资产责任。" } },
    { path: "/business-result-reviews", name: "business-result-reviews", component: BusinessResultReviewView, meta: { requiresAuth: true, uiGroup: "异常与核查", uiTitle: "代确认结果核查", uiDesc: "资产管理员共享查看，不因查看保留审批权。请核验原签收凭证与业务事实，再按现有业务流程处理；核查请求不自动解除责任。" } },
    { path: "/proxy-confirmations", name: "proxy-confirmations", component: ProxyConfirmationView, meta: { requiresAuth: true, uiGroup: "异常与核查", uiTitle: "异常代确认", uiDesc: "仅用于已经实际交付、原接收人无法操作确认的资产。管理员批准后，当前库管还需明确执行。" } },
    { path: "/business-result-notices", name: "business-result-notices", component: BusinessResultNoticeView, meta: { requiresAuth: true, uiGroup: "异常与核查", uiTitle: "业务结果通知", uiDesc: "代确认执行后资产责任已生效；如有异议，请从业务单据联系管理员核查。" } },
    { path: "/messages", name: "messages", component: MessageCenterView, meta: { requiresAuth: true, uiGroup: "异常与核查", uiTitle: "消息中心", uiDesc: "站内消息与工作台待办是权威事实源；阅读不改变业务状态，也不代替办理。" } },
    { path: "/", name: "home", component: HomeView, meta: { requiresAuth: true, uiGroup: "总览", uiTitle: "当前身份与权限", uiDesc: "页面只展示服务端当前授权结果；每次业务请求仍由服务端重新校验。" } },
    { path: "/login", name: "login", component: LoginView },
    { path: "/password/change", name: "password-change", component: PasswordChangeView, meta: { requiresAuth: true } },
    { path: "/assets", name: "assets", component: AssetLedgerView, meta: { requiresAuth: true, uiGroup: "资产", uiTitle: "资产台账", uiDesc: "查看资产状态、建卡和标签。保管关系由正式业务流程维护。" } },
    { path: "/assets/new", name: "asset-new", component: AssetDraftView, props: true, meta: { requiresAuth: true, uiGroup: "资产", uiTitle: "单个建卡", uiDesc: "填写资产名称与可选的型号、单位、分类、组织和计划入库仓库位置；保存后到资产详情继续办理。" } },
    { path: "/assets/:id", name: "asset-detail", component: AssetDetailView, props: true, meta: { requiresAuth: true, uiGroup: "资产", uiTitle: "资产详情", uiDesc: "复制建卡、修正备注、取消草稿与打印标签只作用于本张资产卡。保管关系由正式业务流程维护。" } },
    { path: "/asset-imports", name: "asset-import", component: AssetImportView, meta: { requiresAuth: true, uiGroup: "资产", uiTitle: "资产批量导入", uiDesc: "下载模板、上传 XLSX、映射字段并逐行预检。" } },
    { path: "/asset-import-reviews", name: "asset-import-review", component: AssetImportReviewView, meta: { requiresAuth: true, uiGroup: "资产", uiTitle: "期初导入核验", uiDesc: "核对预检结果与实物抽样，再决定发布或撤销。" } },
    { path: "/asset-initialization", name: "asset-initialization", component: AssetInitializationView, meta: { requiresAuth: true, uiGroup: "资产", uiTitle: "期初导入窗口", uiDesc: "指定仓库操作员和截止时间。仅窗口内可提交与发布期初资产。" } },
    { path: "/inbound", name: "inbound", component: InboundView, meta: { requiresAuth: true, uiGroup: "资产", uiTitle: "入库管理", uiDesc: "管理员确认后，由一名库管认领并逐项核对实物。" } },
    { path: "/inbound/new", name: "inbound-new", component: InboundDraftView, props: true, meta: { requiresAuth: true, uiGroup: "资产", uiTitle: "建立入库单", uiDesc: "选择待入库资产与同一目标仓库、位置。保存后到入库单详情提交审批。" } },
    { path: "/inbound/:id/edit", name: "inbound-edit", component: InboundDraftView, props: true, meta: { requiresAuth: true, uiGroup: "资产", uiTitle: "修订入库草稿", uiDesc: "按单号回填资产与目标位置。保存仍是草稿，提交审批在详情页进行。" } },
    { path: "/inbound/:id", name: "inbound-detail", component: InboundDetailView, props: true, meta: { requiresAuth: true, uiGroup: "资产", uiTitle: "入库单详情", uiDesc: "提交、审批、认领与逐项验收入库只作用于本单。" } },
    { path: "/requisitions", name: "requisitions", component: RequisitionView, meta: { requiresAuth: true, uiGroup: "业务流转", uiTitle: "领用与借用申请", uiDesc: "按数量申请，由一名管理员确认后分配实物。" } },
    { path: "/requisitions/new", name: "requisition-new", component: RequisitionDraftView, props: true, meta: { requiresAuth: true, uiGroup: "业务流转", uiTitle: "新建申请", uiDesc: "按数量填写需求行，保存后到申请单详情提交管理员确认。" } },
    { path: "/requisitions/:id/edit", name: "requisition-edit", component: RequisitionDraftView, props: true, meta: { requiresAuth: true, uiGroup: "业务流转", uiTitle: "修改草稿", uiDesc: "草稿提交前可增删需求行；已提交单据请从详情页提请范围变更。" } },
    { path: "/requisitions/:id", name: "requisition-detail", component: RequisitionDetailView, props: true, meta: { requiresAuth: true, uiGroup: "业务流转", uiTitle: "领用申请详情", uiDesc: "提交、确认、领取、分配与交接只作用于本单。" } },
    { path: "/loans", name: "loans", component: LoanView, meta: { requiresAuth: true, uiGroup: "业务流转", uiTitle: "借用、延期与退还", uiDesc: "借用中的资产可提请延期或归还；退库由库管实物验收后关闭责任。" } },
    { path: "/loans/:id", name: "loan-detail", component: LoanDetailView, props: true, meta: { requiresAuth: true, uiGroup: "业务流转", uiTitle: "借用详情", uiDesc: "提请延期与申请归还只作用于本条借用记录。" } },
    { path: "/asset-returns", name: "asset-returns", component: AssetReturnView, meta: { requiresAuth: true, uiGroup: "业务流转", uiTitle: "退还与退库", uiDesc: "借用归还与在用资产退库都在这里审批与实物验收。" } },
    { path: "/asset-returns/new", name: "asset-return-new", component: AssetReturnDraftView, props: true, meta: { requiresAuth: true, uiGroup: "业务流转", uiTitle: "提请退库", uiDesc: "选择当前可退库资产并填写原因；提交后到退还单详情审批与验收。" } },
    { path: "/asset-returns/:id", name: "asset-return-detail", component: AssetReturnDetailView, props: true, meta: { requiresAuth: true, uiGroup: "业务流转", uiTitle: "退还单详情", uiDesc: "批准、领取、实物验收与转派只作用于本单。" } },
    { path: "/transfers", name: "transfers", component: TransferView, meta: { requiresAuth: true, uiGroup: "业务流转", uiTitle: "调拨与人员交接", uiDesc: "把资产转移到新的责任模式或位置，并处理人员离职交接。" } },
    { path: "/transfers/new", name: "transfer-new", component: TransferDraftView, props: true, meta: { requiresAuth: true, uiGroup: "业务流转", uiTitle: "新建调拨", uiDesc: "先选定来源资产与目标部门、责任人或仓库仓位并添加明细。保存后到调拨详情提交管理员确认。" } },
    { path: "/transfers/:id", name: "transfer-detail", component: TransferDetailView, props: true, meta: { requiresAuth: true, uiGroup: "业务流转", uiTitle: "调拨详情", uiDesc: "提交、批准、领取与逐项实物交接只作用于本单。目标变更请在调拨目标变更页提请。" } },
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
