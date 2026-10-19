import assert from "node:assert/strict";
import test from "node:test";
import { createInventoryReviewPage } from "../src/inventory-review-pages.ts";
import { MiniRequestError } from "../src/session.ts";

const event = (id, field) => ({ currentTarget: { dataset: { id, field } } });
function fixture() {
    const calls = [], navigations = [];
    const itemId = "9007199254740993";
    const item = { item: { id: itemId, asset_id: "19", recheck_generation: 2,
        snapshot_json: { name: "资产", assetCode: "A-19", custodyType: "PERSONAL" } },
        current: { name: "资产", version: 7, custodyType: "PERSONAL" }, review: { version: 0 }, canAcceptNormal: true };
    const raw = { id: "31", recheck_generation: 2, outcome: "CONFLICT_REVIEW", received_at: "2026-10-18T08:00:00Z",
        facts_json: { rawObservedAt: "2026-10-18T07:00:00.125Z", actual: { condition: "NORMAL" } } };
    const selection = { taskVersion: 4, recheckGeneration: 2, review: { version: 1, observation_id: "31", review_status: "SELECTED" }, contextDigest: "abc", history: [], total: 0 };
    const subjects = [{ observation_id: "31", dimension: "CONFLICT", version: 3, subject_status: "OPEN", links: [{ transfer_id: "82", decision_version: 1 }] }];
    let post = async () => ({}), readOverride = null, key = 0;
    const services = { sessions: { identity: { accountId: "1", bindingId: "2" },
        async request(method, path, body, options) {
            calls.push({ method, path, body: structuredClone(body), options: structuredClone(options) });
            if (method === "POST") return post(path, body, options);
            if (readOverride) return readOverride(path);
            if (path.includes("/evidence-subjects")) return { items: structuredClone(subjects) };
            if (path.includes("/observations?")) return { items: [structuredClone(raw)], total: 1 };
            if (path.includes("/selection?")) return structuredClone(selection);
            if (path.includes("/evidence-dispositions?")) return { items: [], total: 0 };
            if (path.includes("/transfers/82")) return { id: "82", requestStatus: "PENDING_APPROVAL", items: [] };
            return { task: { id: "9", name: "盘点", version: 4, task_status: "IN_PROGRESS" }, items: [structuredClone(item)], total: 1, reviewedCount: 0 };
        } }, createKey: () => `key-${++key}`, confirm: async () => true, navigate: path => navigations.push(path) };
    const page = createInventoryReviewPage(() => services); page.data = structuredClone(page.data);
    page.setData = value => Object.assign(page.data, value);
    const open = async () => { page.onLoad({ taskId: "9" }); await page.onShow(); await page.onOpenItem(event(itemId)); await page.onOpenObservation(event("31"));
        page.onField({ currentTarget: { dataset: { field: "reason" } }, detail: { value: "共同核对依据" } });
        page.onField({ currentTarget: { dataset: { field: "evidence" } }, detail: { value: "已核对原始事实及关联流转的明确证据" } }); };
    return { page, services, calls, navigations, item, itemId, selection, subjects, raw, open,
        setPost(value) { post = value; }, setRead(value) { readOverride = value; } };
}

test("native normal review captures exact opaque IDs and all current versions", async () => {
    const f = fixture(); await f.open();
    f.page.onNormalSelection({ detail: { value: [f.itemId, "unowned"] } });
    await f.page.onNormalAcceptance(); const call = f.calls.find(row => row.method === "POST");
    assert.equal(call.path, "/api/v1/mini/inventory-reviews/tasks/9/normal-acceptance");
    assert.deepEqual(call.body.items, [{ itemId: f.itemId, recheckGeneration: 2, reviewVersion: 0, assetVersion: 7 }]);
    assert.equal(call.options.version, 4); assert.equal(call.options.key, "key-1");
});

test("native evidence verification submits every special dimension with original versions", async () => {
    const f = fixture(); f.subjects.push({ observation_id: "31", dimension: "TIME", version: 5, subject_status: "OPEN" },
        { observation_id: "31", dimension: "LOCATION", version: 7, subject_status: "OPEN" });
    await f.open(); await f.page.onEvidenceDecision();
    const call = f.calls.find(row => row.method === "POST");
    assert.equal(call.body.method, "VERIFY_SELECTED");
    assert.deepEqual(call.body.subjects, [{ dimension: "CONFLICT", version: 3 }, { dimension: "TIME", version: 5 }]);
    assert.equal(call.body.contextDigest, "abc"); assert.equal(call.body.reviewVersion, 1);
});

test("uncertain reply survives hide and authorized refresh and replays identical UUID body and versions", async () => {
    const f = fixture(); await f.open(); let first = true;
    f.setPost(async () => { if (first) { first = false; throw new Error("lost response"); } return {}; });
    await f.page.onFinalAcceptance(); assert.equal(f.page.data.retryable, true);
    f.page.onHide(); assert.equal(f.page.data.board, null);
    f.selection.taskVersion = 99; f.selection.review.version = 9;
    await f.page.onShow(); assert.equal(f.page.data.retryable, true); assert.equal(f.page.data.selection.taskVersion, 99);
    await f.page.onRetry(); const posts = f.calls.filter(row => row.method === "POST");
    assert.equal(posts.length, 2); assert.deepEqual(posts[1], posts[0]); assert.equal(f.page.data.retryable, false);
});

test("binding change during confirmation prevents the captured mutation", async () => {
    const f = fixture(); await f.open(); let confirm;
    f.services.confirm = () => new Promise(resolve => { confirm = resolve; });
    const operation = f.page.onFinalAcceptance();
    f.services.sessions.identity = { accountId: "1", bindingId: "new-binding" }; confirm(true); await operation;
    assert.equal(f.calls.filter(row => row.method === "POST").length, 0);
    assert.equal(f.page.data.board, null); assert.equal(f.page.data.observation, null);
});

test("hiding while confirmation is open prevents mutation after the dialog resolves", async () => {
    const f = fixture(); await f.open(); let confirm;
    f.services.confirm = () => new Promise(resolve => { confirm = resolve; });
    const operation = f.page.onFinalAcceptance(); f.page.onHide(); confirm(true); await operation;
    assert.equal(f.calls.filter(row => row.method === "POST").length, 0); assert.equal(f.page.data.board, null);
});

test("current role denial clears private facts targets forms and uncertain receipt", async () => {
    const f = fixture(); await f.open(); f.setPost(async () => { throw new Error("lost"); }); await f.page.onFinalAcceptance();
    f.setRead(async () => { throw new MiniRequestError(403, "INVENTORY_REVIEW_ADMIN_REQUIRED"); });
    await f.page.onRefresh(); assert.equal(f.page.data.board, null); assert.equal(f.page.data.selection, null);
    assert.deepEqual(f.page.data.subjects, []); assert.equal(f.page.data.evidence, ""); assert.equal(f.page.data.retryable, false);
    await f.page.onRetry(); assert.equal(f.calls.filter(row => row.method === "POST").length, 1);
});

test("transport-cleared identity on 401 hides facts and returns to login", async () => {
    const f = fixture(); await f.open();
    f.setRead(async () => { f.services.sessions.identity = null; throw new MiniRequestError(401, "AUTH_SESSION_INVALID"); });
    await f.page.onRefresh(); assert.equal(f.page.data.board, null); assert.equal(f.page.data.busy, false);
    assert.deepEqual(f.navigations, ["/pages/login/index"]);
});

test("formal transfer details are read only and restricted to returned subject links", async () => {
    const f = fixture(); await f.open();
    await f.page.onViewTransfer(event("999")); assert.equal(f.page.data.linked, null);
    await f.page.onViewTransfer(event("82")); assert.equal(f.page.data.linked.id, "82");
    assert.equal(f.calls.filter(row => row.path.includes("/transfers/")).length, 1);
    assert.ok(f.calls.every(row => row.method === "GET"));
});

test("explicit conflict response is definitive and displays actual processor without a replay", async () => {
    const f = fixture(); await f.open(); f.setPost(async () => { throw new MiniRequestError(409, "INVENTORY_REVIEW_VERSION_CONFLICT",
        { processedBy: "other-reviewer", processedAt: "2026-10-18T08:00:00Z", result: "CLOSED" }); });
    await f.page.onFinalAcceptance(); assert.equal(f.page.data.retryable, false); assert.match(f.page.data.error, /other-reviewer/);
    await f.page.onRetry(); assert.equal(f.calls.filter(row => row.method === "POST").length, 1);
});

test("unselected evidence rejection carries every required dimension rather than checked physical rows", async () => {
    const f = fixture(); f.selection.review.observation_id = "32";
    f.subjects.push({ observation_id: "31", dimension: "LOCATION", version: 4, subject_status: "OPEN" });
    await f.open(); f.page.onDimensions({ detail: { value: ["LOCATION"] } }); await f.page.onEvidenceDecision();
    const post = f.calls.find(row => row.method === "POST"); assert.equal(post.body.method, "REJECT_EVIDENCE");
    assert.deepEqual(post.body.subjects, [{ dimension: "CONFLICT", version: 3 }, { dimension: "LOCATION", version: 4 }]);
});

test("late prior-binding board cannot restore private facts after a new binding is shown", async () => {
    const f = fixture(); await f.open(); let release;
    f.setRead(() => new Promise(resolve => { release = resolve; }));
    const old = f.page.onRefresh(); f.page.onHide();
    f.services.sessions.identity = { accountId: "other", bindingId: "other" };
    f.setRead(async () => ({ task: { id: "9", version: 1 }, items: [], total: 0, reviewedCount: 0 }));
    await f.page.onShow(); release({ task: { id: "9", name: "Prior private facts" }, items: [f.item], total: 1 }); await old;
    assert.deepEqual(f.page.data.board.items, []); assert.equal(f.page.data.item, null); assert.equal(f.page.data.busy, false);
});

test("formal target uses current owner catalogs and reference versions without granting execution", async () => {
    const f = fixture(); await f.open(); const rows = {
        organization: [{ id: "101", name: "目标部门", version: 2 }],
        location: [{ id: "102", name: "办公室", version: 3, locationType: "ROOM" }],
        employee: [{ id: "103", name: "责任人", version: 4, organizationId: "101", sharedEligible: true }]
    };
    f.setRead(async path => {
        const kind = /catalog\/(\w+)/.exec(path)?.[1]; if (kind) return { items: rows[kind] ?? [], total: rows[kind]?.length ?? 0 };
        return { task: { id: "9", version: 4 }, items: [], total: 0 };
    });
    await f.page.onPhysicalMethod({ detail: { value: "2" } });
    f.page.onTargetChoice(event("101", "using"));
    await f.page.onCatalog({ currentTarget: { dataset: { kind: "employee" } } });
    assert.ok(f.calls.at(-1).path.endsWith("&organizationId=101")); f.page.onTargetChoice(event("103", "employee"));
    await f.page.onCatalog({ currentTarget: { dataset: { kind: "location" } } }); f.page.onTargetChoice(event("102", "location"));
    f.page.onCustody({ detail: { value: "1" } }); f.page.onDimensions({ detail: { value: ["CONFLICT"] } });
    await f.page.onPhysicalDecision(); const post = f.calls.find(row => row.method === "POST");
    assert.equal(post.body.method, "FORMAL_TRANSFER"); assert.deepEqual(post.body.transferTarget,
        { owningOrganizationId: null, custodyType: "SHARED", usingOrganizationId: "101", employeeId: "103", warehouseId: null,
            locationId: "102", referenceVersions: { location: 3, usingOrganization: 2, employee: 4 } });
    assert.ok(f.calls.filter(row => row.method === "POST").every(row => row.path.endsWith("/differences/decisions")));
});

test("time selection preserves milliseconds and refuses malformed seconds without posting", async () => {
    const f = fixture(); f.raw.outcome = "TIME_REVIEW"; await f.open();
    f.page.onField({ currentTarget: { dataset: { field: "verifiedSecond" } }, detail: { value: "99" } });
    await f.page.onSelectEvidence(); assert.equal(f.calls.filter(row => row.method === "POST").length, 0);
    f.page.onField({ currentTarget: { dataset: { field: "verifiedSecond" } }, detail: { value: "00.125" } });
    await f.page.onSelectEvidence(); const post = f.calls.find(row => row.method === "POST");
    assert.equal(post.body.verifiedObservedAt, "2026-10-18T07:00:00.125Z");
    assert.equal(post.body.firstPersistedAt, undefined); assert.equal(post.body.expiresAt, undefined);
});

const photoEvent = "22222222-2222-4222-8222-222222222222", photoObject = "33333333-3333-4333-8333-333333333333";
const photoMetadata = { objectId: photoObject, filename: "现场原图.png", contentType: "image/png", sizeBytes: 3, scanStatus: "CLEAN" };
async function photoFixture() {
    const f = fixture(); f.raw.client_event_id = photoEvent; f.raw.facts_json.photoIds = [photoObject]; await f.open();
    f.setRead(async () => ({ items: [photoMetadata], total: 1 }));
    f.setPost(async () => ({ url: `/api/v1/mini/inventory-observations/tasks/9/events/${photoEvent}/photos/${photoObject}/content?grant=owned` }));
    const discarded = []; f.services.sessions.discardInventoryPhoto = path => discarded.push(path);
    f.services.sessions.downloadInventoryPhoto = async () => "/owned/current.png";
    return { ...f, discarded };
}
test("native raw photo preview is explicit and hiding clears its owned path and metadata", async () => {
    const f = await photoFixture(); await f.page.onPhotos(event("31")); assert.equal(f.page.data.photoTotal, 1);
    await f.page.onReadPhoto(event(photoObject)); assert.equal(f.page.data.photoPath, "/owned/current.png");
    f.page.onHide(); assert.deepEqual(f.discarded, ["/owned/current.png"]); assert.equal(f.page.data.photoPath, ""); assert.deepEqual(f.page.data.photos, []);
});
test("a late old-page photo is discarded without clearing a newer preview", async () => {
    const f = await photoFixture(); await f.page.onPhotos(event("31")); const releases = [];
    f.services.sessions.downloadInventoryPhoto = () => new Promise(resolve => releases.push(resolve));
    const old = f.page.onReadPhoto(event(photoObject)); await new Promise(resolve => setImmediate(resolve)); f.page.onHide();
    f.setRead(null); await f.page.onShow(); await f.page.onOpenItem(event(f.itemId)); await f.page.onOpenObservation(event("31"));
    f.setRead(async () => ({ items: [photoMetadata], total: 1 })); await f.page.onPhotos(event("31"));
    const fresh = f.page.onReadPhoto(event(photoObject)); await new Promise(resolve => setImmediate(resolve));
    releases[1]("/owned/new.png"); await fresh; releases[0]("/owned/old.png"); await old;
    assert.equal(f.page.data.photoPath, "/owned/new.png"); assert.deepEqual(f.discarded, ["/owned/old.png"]);
    f.page.onUnload(); assert.deepEqual(f.discarded, ["/owned/old.png", "/owned/new.png"]);
});
test("failed local deletion hides the image and blocks another read until cleanup succeeds", async () => {
    const f = await photoFixture(); await f.page.onPhotos(event("31")); let downloads = 0;
    f.services.sessions.downloadInventoryPhoto = async () => { downloads++; return "/owned/current.png"; };
    await f.page.onReadPhoto(event(photoObject)); f.services.sessions.discardInventoryPhoto = () => { throw new Error("owned cleanup failure"); };
    f.page.onClosePhoto(); assert.equal(f.page.data.photoPath, ""); assert.equal(f.page.data.photoCleanupPending, true);
    await f.page.onReadPhoto(event(photoObject)); assert.equal(downloads, 1);
    f.services.sessions.discardInventoryPhoto = path => f.discarded.push(path); f.page.onPhotoCleanup();
    assert.equal(f.page.data.photoCleanupPending, false); await f.page.onReadPhoto(event(photoObject)); assert.equal(downloads, 2); f.page.onUnload();
});
test("photo authorization loss clears the entire old review and login-cleared transport identity", async () => {
    const f = await photoFixture(); await f.page.onPhotos(event("31"));
    f.services.sessions.downloadInventoryPhoto = async () => { f.services.sessions.identity = null; throw new MiniRequestError(401, "AUTH_SESSION_INVALID"); };
    await f.page.onReadPhoto(event(photoObject)); assert.equal(f.page.data.board, null); assert.equal(f.page.data.photoPath, "");
    assert.deepEqual(f.page.data.photos, []); assert.deepEqual(f.navigations, ["/pages/login/index"]);
});
test("a missing photo cannot preserve old private data when current task authorization is denied", async () => {
    const f = await photoFixture(); f.setRead(async path => { throw new MiniRequestError(path.includes("inventory-reviews") ? 403 : 404, "AUTH_FORBIDDEN"); });
    await f.page.onPhotos(event("31")); assert.equal(f.page.data.board, null); assert.deepEqual(f.page.data.photos, []);
});
test("unreadable expired original photo references remain listed after current task authorization", async () => {
    const f = await photoFixture(); f.page.data.observations[0].outcome = "EXPIRED"; f.page.data.observations[0].eligible = false;
    f.setRead(async path => { if (!path.includes("inventory-reviews")) throw new MiniRequestError(404, "INVENTORY_PHOTO_NOT_FOUND"); return {}; });
    await f.page.onPhotos(event("31")); assert.notEqual(f.page.data.board, null); assert.equal(f.page.data.photoTotal, 1);
    assert.equal(f.page.data.photos[0].objectId, photoObject); assert.equal(f.page.data.photos[0].photo, null);
});
test("native checkbox presentation follows cleared normal and dimension selection after refresh", async () => {
    const f = fixture(); await f.open(); f.page.onNormalSelection({ detail: { value: [f.itemId] } });
    f.page.onDimensions({ detail: { value: ["CONFLICT"] } });
    assert.equal(f.page.data.board.items[0].normalChecked, true); assert.equal(f.page.data.subjects[0].checked, true);
    await f.page.onRefresh(); assert.deepEqual(f.page.data.normalIds, []); assert.deepEqual(f.page.data.dimensions, []);
    assert.equal(f.page.data.board.items[0].normalChecked, false); assert.equal(f.page.data.subjects[0].checked, false);
});
