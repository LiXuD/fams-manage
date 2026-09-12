import fs from 'node:fs';
import path from 'node:path';
import crypto from 'node:crypto';
import {connect, read, save} from './local-figma-client.mjs';

const root = path.resolve('docs/superpowers/designs/2026-09-06-fams-ui');
const svgDir = path.join(root, 'figma-native', 'svg-icons');
const journalPath = path.join(root, 'figma-native', 'd04-native-svg-nav-wiring-journal.json');
const pageId = '3:5';

// Keep the existing 24×24 Figma icon slots and replace only their placeholder
// geometry with imported, editable VECTOR descendants. This preserves the
// already verified mobile-nav layout and makes the SVG delivery auditable.
const assetByLabel = {
  首页: ['home-active.svg', 'home-neutral.svg'],
  扫码: ['scan-active.svg', 'scan-neutral.svg'],
  我的: ['mine-active.svg', 'mine-neutral.svg'],
  消息: ['message-active.svg', 'message-neutral.svg'],
};

const flatten = node => [node, ...(node.children || []).flatMap(flatten)];
const parseScan = value => {
  if (Array.isArray(value)) return value;
  const text = String(value || '');
  const start = text.indexOf('[\n');
  return start >= 0 ? JSON.parse(text.slice(start)) : [];
};
const readJournal = () => fs.existsSync(journalPath)
  ? JSON.parse(fs.readFileSync(journalPath, 'utf8'))
  : {steps: {}, createdAt: new Date().toISOString()};
const writeJournal = journal => fs.writeFileSync(journalPath, JSON.stringify(journal, null, 2) + '\n');

const api = await connect();
const journal = readJournal();
const step = async (key, tool, args) => {
  if (Object.hasOwn(journal.steps, key)) return journal.steps[key];
  const value = await api.call(tool, args);
  journal.steps[key] = value;
  writeJournal(journal);
  return value;
};

const findByName = (node, name) => {
  if (node?.name === name) return node;
  for (const child of node?.children || []) {
    const found = findByName(child, name);
    if (found) return found;
  }
  return null;
};
const findText = node => {
  if (node?.type === 'TEXT') return node.characters || '';
  return (node?.children || []).map(findText).join('');
};
const discoverSlots = async () => {
  const maps = read('screens-map.json');
  const discovered = [];
  const screensWithoutNav = [];
  const mobileScreens = Object.entries(maps)
    .filter(([screen]) => /^M\d+$/.test(screen))
    .sort(([a], [b]) => a.localeCompare(b, undefined, {numeric: true}));
  for (const [screen, mapping] of mobileScreens) {
    const rootNode = await api.call('get_node_info', {nodeId: mapping.nodeId});
    const nav = findByName(rootNode, '小程序导航');
    if (!nav) {
      screensWithoutNav.push(screen);
      continue;
    }
    for (const item of (nav.children || []).filter(child => child.name === 'active' || child.name === 'BUTTON')) {
      const slot = (item.children || []).find(child => child.absoluteBoundingBox?.width === 24 && child.absoluteBoundingBox?.height === 24);
      const labelFrame = (item.children || []).find(child => child.name === 'd04-nav-label');
      const label = findText(labelFrame);
      if (!slot || !assetByLabel[label]) throw new Error(`${screen}: unable to map nav item ${item.id} to a 24x24 SVG asset`);
      const active = item.name === 'active';
      discovered.push({
        screen,
        item: item.id,
        slot: slot.id,
        asset: assetByLabel[label][active ? 0 : 1],
        label,
        state: active ? '当前' : '非当前',
      });
    }
  }
  return {slots: discovered, screensWithoutNav};
};

try {
  const discovered = await discoverSlots();
  const slots = discovered.slots;
  if (slots.length !== 52) throw new Error(`Expected 52 bottom-nav slots across 13 mobile pages, found ${slots.length}`);
  const result = [];
  for (const entry of slots) {
    const svgPath = path.join(svgDir, entry.asset);
    if (!fs.existsSync(svgPath)) throw new Error(`Missing SVG asset: ${svgPath}`);
    const sourceSha256 = crypto.createHash('sha256').update(fs.readFileSync(svgPath)).digest('hex');
    const slotBefore = await api.call('get_node_info', {nodeId: entry.slot});
    const beforeChildren = slotBefore.children || [];
    const existingScan = parseScan(await api.call('scan_nodes_by_types', {
      nodeId: entry.slot,
      types: ['VECTOR'],
    }));
    const existingRoot = beforeChildren.find(child => String(child.name || '').startsWith('D0.4 SVG / '));

    if (!existingRoot && existingScan.length === 0) {
      const placeholderIds = beforeChildren.map(child => child.id);
      if (placeholderIds.length) {
        await step(`clear-${entry.slot}`, 'delete_multiple_nodes', {nodeIds: placeholderIds});
      }
      await step(`rename-slot-${entry.slot}`, 'rename_node', {
        nodeId: entry.slot,
        name: `SVG 插槽 / ${entry.screen} / ${entry.label} / ${entry.state}`,
      });
      await step(`import-${entry.slot}`, 'create_node_from_svg', {
        svgPath,
        parentId: entry.slot,
        x: 0,
        y: 0,
        name: `D0.4 SVG / ${entry.screen} / ${entry.label} / ${entry.state}`,
        scale: 1,
      });
    }

    const slotAfter = await api.call('get_node_info', {nodeId: entry.slot});
    const imported = (slotAfter.children || []).find(child => String(child.name || '').startsWith('D0.4 SVG / '));
    if (!imported) throw new Error(`${entry.screen} ${entry.label}: imported SVG root not found in ${entry.slot}`);
    const vectors = parseScan(await api.call('scan_nodes_by_types', {
      nodeId: imported.id,
      types: ['VECTOR'],
    })).filter(node => node.type === 'VECTOR');
    const svgExport = await api.call('export_node_as_image', {
      nodeId: imported.id,
      format: 'SVG',
      scale: 1,
    });
    const pngExport = await api.call('export_node_as_image', {
      nodeId: imported.id,
      format: 'PNG',
      scale: 1,
    });
    result.push({
      screen: entry.screen,
      itemId: entry.item,
      slotId: entry.slot,
      slotName: slotAfter.name,
      asset: entry.asset,
      state: entry.state,
      label: entry.label,
      sourceSha256,
      importedRoot: {
        id: imported.id,
        name: imported.name,
        type: imported.type,
        box: imported.absoluteBoundingBox,
        vectorCount: vectors.length,
        vectors: vectors.map(vector => ({id: vector.id, name: vector.name, type: vector.type})),
        svgExport: svgExport.images?.[0] || null,
        pngExport: pngExport.images?.[0] || null,
      },
      checks: {
        slotIs24x24: slotAfter.absoluteBoundingBox?.width === 24 && slotAfter.absoluteBoundingBox?.height === 24,
        rootIs24x24: imported.absoluteBoundingBox?.width === 24 && imported.absoluteBoundingBox?.height === 24,
        hasEditableVectors: vectors.length > 0,
        hasSvgExport: Boolean(svgExport.images?.[0]),
        noImageFills: flatten(slotAfter).flatMap(node => node.fills || []).every(fill => fill.type !== 'IMAGE'),
      },
    });
  }

  const allPass = result.every(item => Object.values(item.checks).every(Boolean));
  const evidence = {
    verifiedAt: new Date().toISOString(),
    fileKey: 'N4BgxhOTtSFpsoxcN7t4XD',
    channel: process.env.FAMS_FIGMA_CHANNEL || 'v7dkvp2o',
    page: {id: pageId, name: '03 · Screens & Handoff'},
    scope: 'All M02–M14 bottom-nav 24×24 icon slots present in the native page bodies',
    mapping: result,
    totals: {
      slots: result.length,
      importedRoots: result.filter(item => item.importedRoot.type === 'FRAME').length,
      vectorLayers: result.reduce((sum, item) => sum + item.importedRoot.vectorCount, 0),
      svgExports: result.filter(item => item.importedRoot.svgExport).length,
    },
    discovery: {
      mobileScreensWithNav: [...new Set(result.map(item => item.screen))].sort(),
      mobileScreensWithoutNav: discovered.screensWithoutNav,
    },
    checks: {
      allExpectedSlotsWired: result.length === slots.length && slots.length === 52,
      representativeTwelveWired: result.filter(item => ['M02', 'M05', 'M14'].includes(item.screen)).length === 12,
      everySlotPasses: allPass,
      everyImportHasEditableVector: result.length === slots.length && result.every(item => item.checks.hasEditableVectors),
      everyImportHasSvgExport: result.length === slots.length && result.every(item => item.checks.hasSvgExport),
      noImageFills: result.every(item => item.checks.noImageFills),
    },
    limitations: {
      remainingVisualScope: 'The 40 business screen bodies remain synced as existing native editable content; M02–M14 bottom-nav slots are wired where the source screen contains a bottom navigation frame. M01 intentionally has no bottom navigation frame.',
      productionAcceptance: 'Not claimed: this workspace has no production service or real-device harness.',
    },
  };
  save('d04-native-svg-nav-wiring.json', evidence);
  console.log(JSON.stringify({
    verifiedAt: evidence.verifiedAt,
    totals: evidence.totals,
    checks: evidence.checks,
    mapping: result.map(item => ({screen: item.screen, slotId: item.slotId, importedRootId: item.importedRoot.id, vectorCount: item.importedRoot.vectorCount})),
  }, null, 2));
} finally {
  await api.close();
}
