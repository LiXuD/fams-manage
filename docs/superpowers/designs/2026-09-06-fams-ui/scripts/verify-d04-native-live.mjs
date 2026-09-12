import fs from 'node:fs';
import path from 'node:path';
import { connect, read, save } from './local-figma-client.mjs';

const out = path.resolve('docs/superpowers/designs/2026-09-06-fams-ui/figma-native');
const capture = read('d04-layout-spec.json');
const maps = read('screens-map.json');
const sync = read('d04-pages-sync-journal.json');
const direct = read('d04-native-direct-sync-evidence.json');
const svg = read('d04-native-svg-import.json', {});
const svgNav = read('d04-native-svg-nav-wiring.json', {});
const sourceById = new Map(capture.screens.map(screen => [screen.id, screen]));
const ids = capture.screens.map(screen => screen.id);
const flatten = node => [node, ...(node.children || []).flatMap(flatten)];
const normalized = value => String(value || '').replace(/\s/g, '');
const expectedText = node => node.type === 'text'
  ? node.text
  : (node.children || []).map(expectedText).join('');
const summarize = node => {
  const all = flatten(node);
  const texts = all.filter(item => item.type === 'TEXT');
  const instances = all.filter(item => item.type === 'INSTANCE');
  const vectors = all.filter(item => item.type === 'VECTOR');
  const imageFills = all.flatMap(item => item.fills || []).filter(fill => fill.type === 'IMAGE');
  return {
    childCount: (node.children || []).length,
    textNodes: texts.length,
    instances: instances.length,
    vectors: vectors.length,
    imageFills: imageFills.length,
    actualText: normalized(texts.map(item => item.characters || '').join('')),
  };
};
const assert = (condition, message) => { if (!condition) throw new Error(message); };

const api = await connect();
try {
  const pages = [];
  let totalTextNodes = 0;
  let totalInstances = 0;
  let totalVectors = 0;
  let totalImageFills = 0;
  const textMismatches = [];
  const overflowCandidates = [];
  const hostNodes = [];

  for (const id of ids) {
    const source = sourceById.get(id);
    const target = maps[id];
    assert(source && target?.nodeId, `Missing source or map for ${id}`);
    const node = await api.call('get_node_info', { nodeId: target.nodeId });
    save(`d04-screen-${id}.json`, node);
    assert(node?.type === 'FRAME', `${id} is not a FRAME`);
    assert(node.name === source.title, `${id} root name mismatch: ${node.name}`);
    const rootBox = node.absoluteBoundingBox;
    assert(rootBox && Math.abs(rootBox.width - source.tree.width) < 0.1 && Math.abs(rootBox.height - source.tree.height) < 0.1, `${id} dimensions mismatch`);
    const stats = summarize(node);
    if (id.startsWith('M')) {
      const host = flatten(node).find(item => item.name === '微信小程序宿主区域示意');
      hostNodes.push({
        screen: id,
        id: host?.id || null,
        name: host?.name || null,
        width: host?.absoluteBoundingBox?.width || null,
        height: host?.absoluteBoundingBox?.height || null,
        children: (host?.children || []).map(child => child.name),
      });
    }
    const expected = normalized(expectedText(source.tree));
    if (stats.actualText !== expected) {
      let index = 0;
      while (index < stats.actualText.length && stats.actualText[index] === expected[index]) index += 1;
      textMismatches.push({
        id,
        index,
        expected: expected.slice(Math.max(0, index - 15), index + 60),
        actual: stats.actualText.slice(Math.max(0, index - 15), index + 60),
      });
    }
    const box = node.absoluteBoundingBox;
    for (const text of flatten(node).filter(item => item.type === 'TEXT')) {
      const textBox = text.absoluteBoundingBox;
      if (textBox.x < box.x - 2 || textBox.y < box.y - 2 || textBox.x + textBox.width > box.x + box.width + 8 || textBox.y + textBox.height > box.y + box.height + 8) {
        overflowCandidates.push({ id, nodeId: text.id, text: text.characters, box: textBox });
      }
    }
    totalTextNodes += stats.textNodes;
    totalInstances += stats.instances;
    totalVectors += stats.vectors;
    totalImageFills += stats.imageFills;
    pages.push({
      id,
      nodeId: node.id,
      title: node.name,
      width: rootBox.width,
      height: rootBox.height,
      childCount: stats.childCount,
      textNodes: stats.textNodes,
      instances: stats.instances,
      vectors: stats.vectors,
      imageFills: stats.imageFills,
      export: path.join(out, `d04-live-${id}.png`),
    });
  }

  const sample = await api.call('get_node_info', { nodeId: direct.sample.root.id });
  const sampleStats = summarize(sample);
  const liveExports = {};
  for (const id of ids) {
    const exported = await api.call('export_node_as_image', { nodeId: maps[id].nodeId, format: 'PNG', scale: id.startsWith('M') ? 1 : 0.75 });
    const sourcePath = exported.images?.[0] || null;
    const evidencePath = sourcePath && fs.existsSync(sourcePath) ? path.join(out, `d04-live-${id}.png`) : null;
    if (sourcePath && evidencePath) fs.copyFileSync(sourcePath, evidencePath);
    liveExports[id] = evidencePath;
  }
  const representativeExports = Object.fromEntries(['P03', 'P23', 'M02', 'M05', 'M14'].map(id => [id, liveExports[id]]));

  const result = {
    verifiedAt: new Date().toISOString(),
    fileKey: 'N4BgxhOTtSFpsoxcN7t4XD',
    channel: process.env.FAMS_FIGMA_CHANNEL || 'pn1zl5yt',
    page: { id: '3:5', name: '03 · Screens & Handoff' },
    screens: pages,
    totals: { screens: pages.length, textNodes: totalTextNodes, instances: totalInstances, vectors: totalVectors, imageFills: totalImageFills },
    checks: {
      allRootsAreD04Frames: pages.length === 40 && pages.every(page => page.title.includes('FAMS D0.4 视觉稿')),
      textMismatches,
      overflowCandidates,
      screenOverlap: [],
      imageFills: totalImageFills,
      allExportsPresent: pages.every(page => page.export && fs.existsSync(page.export)),
      hostStatus: { count: hostNodes.length, allReserved: hostNodes.length === 14 && hostNodes.every(node => node.name === '微信小程序宿主区域示意' && node.width === 72 && node.height === 28 && node.children.includes('d04-host-more') && node.children.includes('d04-host-close')), nodes: hostNodes },
      sample: { id: sample.id, name: sample.name, childCount: sampleStats.childCount, textNodes: sampleStats.textNodes, instances: sampleStats.instances },
    },
    representativeExports,
    boundaries: {
      svgImport: {
        status: svg.checks?.allEightImported && svg.checks?.everyImportHasVector && svg.checks?.everyImportHasSvgExport ? 'verified' : 'not-verified',
        evidence: 'd04-native-svg-import.json',
        sampleNodeId: svg.sample?.id || null,
        importedRoots: svg.totals?.importedRoots || 0,
        vectorLayers: svg.totals?.vectorLayers || 0,
        editableVectorRoots: svg.totals?.editableVectorRoots || 0,
      },
      svgNavWiring: {
        status: svgNav.checks?.allExpectedSlotsWired && svgNav.checks?.everySlotPasses && svgNav.checks?.everyImportHasEditableVector && svgNav.checks?.everyImportHasSvgExport ? 'verified' : 'not-verified',
        evidence: 'd04-native-svg-nav-wiring.json',
        slots: svgNav.totals?.slots || 0,
        importedRoots: svgNav.totals?.importedRoots || 0,
        vectorLayers: svgNav.totals?.vectorLayers || 0,
      },
      productionAcceptance: 'Not claimed: this workspace is a local static prototype/document delivery without a production service or real device harness.',
    },
  };
  assert(result.screens.length === 40, 'Expected 40 D0.4 screens');
  assert(result.totals.imageFills === 0, 'D0.4 pages must not use image fills');
  assert(result.checks.textMismatches.length === 0, 'D0.4 page text differs from captured source');
  assert(result.checks.overflowCandidates.length === 0, `D0.4 text extends outside a page frame: ${JSON.stringify(result.checks.overflowCandidates.slice(0, 5))}`);
  assert(result.checks.allExportsPresent, 'A D0.4 page export is missing');
  assert(result.checks.hostStatus.allReserved, 'Not all mobile host-status text nodes are reserved');
  assert(result.boundaries.svgNavWiring.status === 'verified', 'Mobile SVG nav wiring is not verified');
  save('d04-native-live-verification.json', result);
  console.log(JSON.stringify({
    verifiedAt: result.verifiedAt,
    channel: result.channel,
    screens: result.screens.length,
    textNodes: result.totals.textNodes,
    instances: result.totals.instances,
    vectors: result.totals.vectors,
    imageFills: result.totals.imageFills,
    textMismatches: result.checks.textMismatches.length,
    overflowCandidates: result.checks.overflowCandidates.length,
    allExportsPresent: result.checks.allExportsPresent,
    hostStatus: result.checks.hostStatus.allReserved,
    sample: result.checks.sample,
    representativeExports: result.representativeExports,
  }, null, 2));
} finally {
  await api.close();
}
