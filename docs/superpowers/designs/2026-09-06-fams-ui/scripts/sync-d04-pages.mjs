import fs from 'node:fs';
import path from 'node:path';
import { connect, read, save } from './local-figma-client.mjs';
import { journal, renderer } from './native-renderer.mjs';

const out = path.resolve('docs/superpowers/designs/2026-09-06-fams-ui/figma-native');
const capture = read('d04-layout-spec.json');
const maps = read('screens-map.json');
const components = read('component-map.json').components;
const requested = process.argv.slice(2).filter(v => /^[PM]\d{2}$/.test(v));
const ids = requested.length ? requested : capture.screens.map(s => s.id);
const sourceById = new Map(capture.screens.map(s => [s.id, s]));
const api = await connect();
const journalFile = 'd04-pages-sync-journal.json';
const j = journal(api, journalFile);
const { state, persist } = j;
const { render } = renderer(api, j, components);
const backup = { capturedAt: new Date().toISOString(), channel: process.env.FAMS_FIGMA_CHANNEL || 'pn1zl5yt', pages: {} };

try {
  for (const id of ids) {
    const source = sourceById.get(id);
    const target = maps[id];
    if (!source || !target?.nodeId) throw new Error(`Missing source or target for ${id}`);

    const before = await api.call('get_node_info', { nodeId: target.nodeId });
    backup.pages[id] = { nodeId: target.nodeId, name: before.name, children: (before.children || []).map(n => ({ id: n.id, name: n.name, type: n.type })) };
    save('d04-pre-body-sync-backup.json', backup);

    const children = (before.children || []).map(n => n.id);
    if (children.length) await api.call('delete_multiple_nodes', { nodeIds: children });

    for (const key of Object.keys(state.steps || {})) if (key === id || key.startsWith(id + '/')) delete state.steps[key];
    state.pages ??= {};
    delete state.pages[id];
    persist();

    await api.call('rename_node', { nodeId: target.nodeId, name: source.title });
    await render({ ...source.tree, name: source.title }, '3:5', { x: -target.x, y: -target.y }, id, target.nodeId);

    const verified = await api.call('get_node_info', { nodeId: target.nodeId });
    const childCount = (verified.children || []).length;
    const textCount = [];
    const walk = n => { if (n.type === 'TEXT') textCount.push(n); for (const c of n.children || []) walk(c); };
    walk(verified);
    if (!childCount || !textCount.length) throw new Error(`${id} rendered without children/text`);
    save(`d04-screen-${id}.json`, verified);
    const exported = await api.call('export_node_as_image', { nodeId: target.nodeId, format: 'PNG', scale: id.startsWith('M') ? 1 : 0.75 });
    const exportPath = exported.images?.[0];
    if (exportPath && fs.existsSync(exportPath)) {
      fs.copyFileSync(exportPath, path.join(out, `d04-${id}.png`));
    }
    state.pages[id] = { nodeId: target.nodeId, title: source.title, childCount, textCount: textCount.length, export: exportPath || null };
    persist();
    console.log(JSON.stringify({ id, nodeId: target.nodeId, childCount, textCount: textCount.length, export: exportPath || null }));
  }
} finally {
  save('d04-pre-body-sync-backup.json', backup);
  await api.close();
}
