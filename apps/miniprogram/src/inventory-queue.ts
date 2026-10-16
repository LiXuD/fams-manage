/** Queue values contain only the current task's authorized facts, never credentials or directories. */
export interface InventoryObservation {
    eventId: string; taskId: string; itemId: string | null; assignmentVersion: number;
    recheckGeneration: number; assetVersion: number | null; deviceId: string;
    source: "SCAN" | "CODE" | "MANUAL"; assetCode: string | null; manualReason: string | null;
    actual: { locationId: string | null; locationHint: string | null;
        custodyType: string | null; responsibleEmployeeId: string | null;
        responsibilityHint: string | null; condition: "GOOD" | "DAMAGED" | "UNKNOWN";
        unknownReason: string | null; description: string | null };
    rawScannedAt?: string | null; rawObservedAt: string; firstPersistedAt: string; expiresAt: string;
    calibrationId: string | null; clockAnomaly: boolean;
    photos: { path: string; name: string; size: number }[];
}
export interface InventoryQueueStorage {
    read(key: string): unknown;
    write(key: string, value: unknown): void;
    photoExists(path: string): Promise<boolean>;
    removePhoto(path: string): Promise<void>;
}
export interface InventoryQueueClock { now(): number }
export interface InventoryCachedItem { id: string; assignment_version: number; recheck_generation: number; asset_version: number;
    snapshot_json: Record<string, string | number | null> }
export interface InventoryCachedTask { id: string; name: string; task_status: string; items: InventoryCachedItem[] }
interface Failure { eventId: string; code: string; taskId?: string; deviceId?: string }
interface Receipt { eventId: string; outcome: string; taskId: string; deviceId: string }
interface Envelope { version: 1; events: InventoryObservation[]; failures: Failure[]; lastClock: number;
    receipts?: Receipt[];
    tasks?: Record<string, Record<string, InventoryCachedTask>>;
    photoReceipts?: Record<string, Record<string, string>>;
    revoked?: Record<string, Record<string, boolean>>;
    calibrations?: Record<string, { calibrationId: string; clientTime: string; serverTime: string }> }
const retention = 72 * 60 * 60 * 1000;
const id = /^[1-9][0-9]*$/;
const uuid = /^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/i;
const clone = <T>(value: T): T => JSON.parse(JSON.stringify(value)) as T;

/** A successful return always follows synchronous storage; network code never creates IDs. */
export class InventoryQueue {
    private readonly key: string;
    private readonly storage: InventoryQueueStorage;
    private readonly clock: InventoryQueueClock;
    private readonly deviceId: string;
    constructor(identity: { accountId: string; bindingId: string }, device: string,
        storage: InventoryQueueStorage, clock: InventoryQueueClock) {
        if (!id.test(identity.accountId) || !id.test(identity.bindingId) || !uuid.test(device)) throw new Error("Invalid queue identity");
        this.key = `fams.identity.${identity.accountId}.${identity.bindingId}`;
        this.storage = storage; this.clock = clock; this.deviceId = device;
    }
    private read(): Envelope {
        const value = this.storage.read(this.key);
        if (value == null) return { version: 1, events: [], failures: [], lastClock: this.clock.now() };
        if (typeof value !== "object" || (value as Envelope).version !== 1
            || !Array.isArray((value as Envelope).events) || !Array.isArray((value as Envelope).failures)
            || !Number.isFinite((value as Envelope).lastClock)) throw new Error("Inventory queue requires recovery");
        const saved = clone(value as Envelope);
        for (const event of saved.events) {
            if (!uuid.test(event.eventId) || !id.test(event.taskId) || !uuid.test(event.deviceId)
                || !Array.isArray(event.photos) || !Number.isFinite(Date.parse(event.firstPersistedAt))
                || Date.parse(event.expiresAt) !== Date.parse(event.firstPersistedAt) + retention) {
                throw new Error("Inventory queue requires recovery");
            }
        }
        return saved;
    }
    private write(value: Envelope): void { this.storage.write(this.key, clone(value)); }
    list(): InventoryObservation[] { return this.read().events.filter(event => event.deviceId === this.deviceId); }
    failures(): Failure[] { return this.read().failures; }
    statuses(taskId: string): { eventId: string; outcome: string }[] {
        const saved = this.read(); const rows = new Map<string, string>();
        for (const failure of saved.failures) if (failure.taskId === taskId && failure.deviceId === this.deviceId) rows.set(failure.eventId, failure.code);
        for (const receipt of saved.receipts ?? []) if (receipt.taskId === taskId && receipt.deviceId === this.deviceId) rows.set(receipt.eventId, receipt.outcome);
        return [...rows].map(([eventId, outcome]) => ({ eventId, outcome }));
    }
    receipt(eventId: string): string | null { return this.read().receipts?.find(row => row.eventId === eventId && row.deviceId === this.deviceId)?.outcome ?? null; }
    photoPaths(): string[] { return this.read().events.flatMap(event => event.photos.map(photo => photo.path)); }
    cache(task: InventoryCachedTask): void {
        if (!id.test(task.id)) throw new Error("Invalid cached task");
        const saved = this.read(); saved.tasks ??= {}; saved.tasks[this.deviceId] ??= {};
        if (saved.revoked?.[this.deviceId]) delete saved.revoked[this.deviceId][task.id];
        saved.tasks[this.deviceId][task.id] = clone(task); this.write(saved);
    }
    cached(taskId: string): InventoryCachedTask | null { const saved = this.read(); return saved.revoked?.[this.deviceId]?.[taskId] ? null : saved.tasks?.[this.deviceId]?.[taskId] ?? null; }
    calibrate(value: { calibrationId: string; clientTime: string; serverTime: string }): void {
        if (!uuid.test(value.calibrationId) || !Number.isFinite(Date.parse(value.clientTime)) || !Number.isFinite(Date.parse(value.serverTime))) throw new Error("Invalid time calibration");
        const saved = this.read(); saved.calibrations ??= {}; saved.calibrations[this.deviceId] = clone(value); this.write(saved);
    }
    calibration(): { calibrationId: string; clientTime: string; serverTime: string } | null { return this.read().calibrations?.[this.deviceId] ?? null; }
    photoReceipt(eventId: string, path: string): string | null { return this.read().photoReceipts?.[eventId]?.[path] ?? null; }
    recordPhotoReceipt(eventId: string, path: string, objectId: string): void {
        if (!uuid.test(objectId)) throw new Error("Invalid photo receipt");
        const saved = this.read();
        if (!saved.events.some(event => event.eventId === eventId && event.deviceId === this.deviceId && event.photos.some(photo => photo.path === path))) throw new Error("Photo event not owned");
        saved.photoReceipts ??= {}; saved.photoReceipts[eventId] ??= {};
        const prior = saved.photoReceipts[eventId][path];
        if (prior && prior !== objectId) throw new Error("Photo receipt identity changed");
        saved.photoReceipts[eventId][path] = objectId; this.write(saved);
    }
    async rejectTask(taskId: string): Promise<void> {
        const state = this.read(); state.revoked ??= {}; state.revoked[this.deviceId] ??= {}; state.revoked[this.deviceId][taskId] = true;
        if (state.tasks?.[this.deviceId]) delete state.tasks[this.deviceId][taskId]; this.write(state);
        for (const event of this.list()) if (event.taskId === taskId) await this.remove(event.eventId, this.receipt(event.eventId) ? null : "AUTHORIZATION_REJECTED");
        const saved = this.read(); if (saved.tasks?.[this.deviceId]) delete saved.tasks[this.deviceId][taskId]; this.write(saved);
    }
    async save(input: Omit<InventoryObservation, "firstPersistedAt" | "expiresAt" | "clockAnomaly" | "deviceId">): Promise<InventoryObservation> {
        const saved = this.read(); const now = this.clock.now();
        if (saved.revoked?.[this.deviceId]?.[input.taskId]) throw new Error("Inventory task authorization revoked");
        if (!Number.isFinite(now) || !uuid.test(input.eventId) || !id.test(input.taskId)
            || saved.events.some(event => event.eventId === input.eventId)) throw new Error("Invalid or duplicate event identity");
        for (const photo of input.photos) {
            if (!(await this.storage.photoExists(photo.path))) throw new Error("Inventory photo is not durable");
        }
        const event: InventoryObservation = { ...clone(input), deviceId: this.deviceId,
            firstPersistedAt: new Date(now).toISOString(), expiresAt: new Date(now + retention).toISOString(),
            clockAnomaly: now < saved.lastClock - 2000 };
        saved.events.push(event); saved.lastClock = Math.max(saved.lastClock, now); this.write(saved);
        return clone(event);
    }
    async uploadable(eventId: string): Promise<InventoryObservation> {
        const saved = this.read(); const event = saved.events.find(row => row.eventId === eventId && row.deviceId === this.deviceId);
        if (!event) throw new Error("Inventory event missing; recollection requires a new identity");
        if (saved.revoked?.[this.deviceId]?.[event.taskId]) throw new Error("Inventory task authorization revoked");
        for (const photo of event.photos) if (!(await this.storage.photoExists(photo.path)) && !saved.photoReceipts?.[eventId]?.[photo.path]) throw new Error("Inventory evidence lost; recollection required");
        // Old observations keep their original clock evidence. The server checks receipt before expiry;
        // a later device rollback cannot prevent recovery of an already committed original receipt.
        return clone(event);
    }
    async acknowledge(eventId: string, outcome: string): Promise<void> {
        if (!["ACCEPTED", "DIFFERENCE_REVIEW", "DUPLICATE", "CONFLICT_REVIEW", "LATE_REVIEW", "OLD_ASSIGNMENT_REVIEW", "FINDING_REVIEW", "TIME_REVIEW", "EXPIRED", "REJECTED"].includes(outcome)) throw new Error("Explicit receipt required");
        const saved = this.read(); const event = saved.events.find(row => row.eventId === eventId && row.deviceId === this.deviceId);
        if (!event) return;
        const prior = saved.receipts?.find(row => row.eventId === eventId && row.deviceId === this.deviceId);
        if (prior && prior.outcome !== outcome) throw new Error("Original receipt changed");
        if (!prior) {
            saved.receipts ??= []; saved.receipts.push({ eventId, taskId: event.taskId, deviceId: this.deviceId, outcome });
            saved.receipts = saved.receipts.slice(-100); this.write(saved);
        }
        await this.remove(eventId, ["REJECTED", "EXPIRED"].includes(outcome) ? outcome : null);
    }
    async expire(): Promise<void> {
        for (const event of this.list()) if (this.clock.now() >= Date.parse(event.expiresAt)) await this.remove(event.eventId, this.receipt(event.eventId) ? null : "EXPIRED_RECOLLECT");
    }
    private async remove(eventId: string, failure: string | null): Promise<void> {
        const saved = this.read(); const event = saved.events.find(row => row.eventId === eventId && row.deviceId === this.deviceId);
        if (!event) return;
        // Delete photos before discarding references so cleanup failure remains recoverable.
        for (const photo of event.photos) await this.storage.removePhoto(photo.path);
        saved.events = saved.events.filter(row => row !== event);
        if (saved.photoReceipts) delete saved.photoReceipts[eventId];
        if (failure) saved.failures.push({ eventId, code: failure, taskId: event.taskId, deviceId: this.deviceId });
        saved.failures = saved.failures.slice(-100); this.write(saved);
    }
}
