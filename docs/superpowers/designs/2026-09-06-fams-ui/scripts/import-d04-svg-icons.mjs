import fs from 'node:fs';
import path from 'node:path';
import crypto from 'node:crypto';
import { connect, save, color } from './local-figma-client.mjs';
import { journal } from './native-renderer.mjs';

const root = path.resolve('docs/superpowers/designs/2026-09-06-fams-ui');
const svgDir = path.join(root, 'figma-native', 'svg-icons');
const stateFile = 'd04-native-svg-import-journal.json';
const pageId = '3:5';
const sample = {
  x: 9200,
  y: 980,
  width: 1000,
  height: 500,
  name: 'D0.4 / SVG 图标原生导入样本',
};
const icons = [
  ['home-active', '首页 / 当前', 70, 112],
  ['home-neutral', '首页 / 非当前', 310, 112],
  ['message-active', '消息 / 当前', 550, 112],
  ['message-neutral', '消息 / 非当前', 790, 112],
  ['scan-active', '扫码 / 当前', 70, 292],
  ['scan-neutral', '扫码 / 非当前', 310, 292],
  ['mine-active', '我的 / 当前', 550, 292],
  ['mine-neutral', '我的 / 非当前', 790, 292],
].map(([key, label, x, y]) => ({key, label, x, y}));

const parseScan = value => {
  if (Array.isArray(value)) return value;
  const text = String(value || '');
  const start = text.indexOf('[\n');
  return start >= 0 ? JSON.parse(text.slice(start)) : [];
};

const api = await connect();
try {
  const j = journal(api, stateFile);
  const frame = await j.step('sample-frame', 'create_frame', {
    parentId: pageId,
    x: sample.x,
    y: sample.y,
    width: sample.width,
    height: sample.height,
    name: sample.name,
    fillColor: color('#FFFFFF'),
    strokeColor: color('#E3E8F0'),
    strokeWeight: 1,
  });
  const frameId = frame.id;
  await j.step('sample-title', 'create_text', {
    parentId: frameId,
    x: 48,
    y: 26,
    text: 'D0.4 / SVG 图标原生导入样本',
    fontSize: 20,
    fontWeight: 700,
    fontColor: color('#18263D'),
    name: '样本标题',
  });
  await j.step('sample-note', 'create_text', {
    parentId: frameId,
    x: 48,
    y: 62,
    text: '8 个图标通过 figma.createNodeFromSvg 导入；保留可编辑 VECTOR 图层，不使用图片填充。',
    fontSize: 12,
    fontWeight: 400,
    fontColor: color('#53657D'),
    name: '样本说明',
  });

  for (const icon of icons) {
    const svgPath = path.join(svgDir, `${icon.key}.svg`);
    if (!fs.existsSync(svgPath)) throw new Error(`Missing SVG asset: ${svgPath}`);
    await j.step(`icon-${icon.key}`, 'create_node_from_svg', {
      svgPath,
      parentId: frameId,
      x: icon.x,
      y: icon.y,
      name: `SVG / ${icon.label}`,
      scale: 3,
    });
    await j.step(`label-${icon.key}`, 'create_text', {
      parentId: frameId,
      x: icon.x - 20,
      y: icon.y + 104,
      text: icon.label,
      fontSize: 12,
      fontWeight: 500,
      fontColor: color('#18263D'),
      name: `标签 / ${icon.label}`,
    });
  }
  j.persist();

  const node = await api.call('get_node_info', {nodeId: frameId});
  const flatten = n => [n, ...(n.children || []).flatMap(flatten)];
  const all = flatten(node);
  const imported = all.filter(n => String(n.name || '').startsWith('SVG / '));
  // get_node_info intentionally omits VECTOR nodes. The type scan is the
  // second, independent readback that proves the imported children remain
  // editable vector layers rather than image fills.
  const scanned = parseScan(await api.call('scan_nodes_by_types', {
    nodeId: frameId,
    types: ['FRAME', 'VECTOR'],
  }));
  const vectors = scanned.filter(n => n.type === 'VECTOR');
  const vectorCounts = {};
  for (const item of imported) {
    const itemScan = parseScan(await api.call('scan_nodes_by_types', {
      nodeId: item.id,
      types: ['VECTOR'],
    }));
    vectorCounts[item.id] = itemScan.filter(n => n.type === 'VECTOR').length;
  }
  const exported = await api.call('export_node_as_image', {nodeId: frameId, format: 'PNG', scale: 1});
  const svgExports = {};
  for (const item of imported) {
    const svg = await api.call('export_node_as_image', {nodeId: item.id, format: 'SVG', scale: 1});
    svgExports[item.id] = svg.images?.[0] || null;
  }
  const evidence = {
    verifiedAt: new Date().toISOString(),
    fileKey: 'N4BgxhOTtSFpsoxcN7t4XD',
    channel: process.env.FAMS_FIGMA_CHANNEL || 'v7dkvp2o',
    page: {id: pageId, name: '03 · Screens & Handoff'},
    sample: {
      id: frameId,
      name: node.name,
      absoluteBoundingBox: node.absoluteBoundingBox,
      pngExport: exported.images?.[0] || null,
      childCount: node.children?.length || 0,
    },
    assets: icons.map(icon => {
      const item = imported.find(n => n.name === `SVG / ${icon.label}`);
      return {
        asset: `${icon.key}.svg`,
        sourceSha256: crypto.createHash('sha256').update(fs.readFileSync(path.join(svgDir, `${icon.key}.svg`))).digest('hex'),
        nodeId: item?.id || null,
        name: item?.name || null,
        type: item?.type || null,
        width: item?.absoluteBoundingBox?.width || null,
        height: item?.absoluteBoundingBox?.height || null,
        vectorCount: item ? vectorCounts[item.id] || 0 : 0,
        svgExport: item ? svgExports[item.id] || null : null,
      };
    }),
    totals: {
      importedRoots: imported.length,
      vectorLayers: vectors.length,
      editableVectorRoots: imported.filter(n => (vectorCounts[n.id] || 0) > 0).length,
    },
    checks: {
      allEightImported: imported.length === 8,
      everyImportHasVector: imported.length === 8 && imported.every(n => (vectorCounts[n.id] || 0) > 0),
      everyImportHasSvgExport: imported.length === 8 && imported.every(n => Boolean(svgExports[n.id])),
      noImageFills: all.flatMap(n => n.fills || []).every(fill => fill.type !== 'IMAGE'),
    },
    limitations: {
      scope: 'Separate native sample frame; existing 40 business screen roots remain unchanged.',
      productionAcceptance: 'Not claimed: this workspace has no production service or real-device harness.',
    },
  };
  save('d04-native-svg-import.json', evidence);
  console.log(JSON.stringify({
    sample: evidence.sample,
    assets: evidence.assets,
    totals: evidence.totals,
    checks: evidence.checks,
  }, null, 2));
} finally {
  await api.close();
}
