import path from 'node:path';
import { connect, read, save } from './local-figma-client.mjs';

const out = path.resolve('docs/superpowers/designs/2026-09-06-fams-ui/figma-native');
const maps = read('screens-map.json');
const screens = ['M01', 'M02', 'M03', 'M04', 'M05', 'M06', 'M07', 'M08', 'M09', 'M10', 'M11', 'M12', 'M13', 'M14'];
const api = await connect();
const findEllipsis = (node, parent = null, grandparent = null) => {
  if (node.type === 'TEXT' && node.characters === '···') return { node, parent, grandparent };
  for (const child of node.children || []) {
    const found = findEllipsis(child, node, parent);
    if (found) return found;
  }
  return null;
};
const round = value => Math.round(value * 100) / 100;

try {
  const backup = { capturedAt: new Date().toISOString(), channel: process.env.FAMS_FIGMA_CHANNEL || 'pn1zl5yt', pages: {} };
  for (const id of screens) {
    const live = await api.call('get_node_info', { nodeId: maps[id].nodeId });
    const found = findEllipsis(live);
    if (!found?.parent || !found.grandparent) throw new Error(`${id}: ellipsis frame ancestry was not found`);
    const root = live.absoluteBoundingBox;
    const text = found.node.absoluteBoundingBox;
    const parent = found.parent.absoluteBoundingBox;
    const grandparent = found.grandparent.absoluteBoundingBox;
    const width = Math.max(parent.width, text.width);
    const targetAbsoluteX = root.x + root.width - 16 - width;
    backup.pages[id] = {
      root: { id: live.id, box: root },
      grandparent: { id: found.grandparent.id, name: found.grandparent.name, box: grandparent },
      parent: { id: found.parent.id, name: found.parent.name, box: parent },
      text: { id: found.node.id, box: text },
      target: { parentWidth: width, parentX: targetAbsoluteX - grandparent.x, parentY: parent.y - grandparent.y, textX: 0, textY: text.y - parent.y },
    };
  }
  save('d04-native-live-pre-ellipsis-frame-repair.json', backup);

  const repaired = [];
  for (const id of screens) {
    const item = backup.pages[id];
    await api.call('move_node', { nodeId: item.text.id, x: item.target.textX, y: item.target.textY });
    await api.call('resize_node', { nodeId: item.parent.id, width: item.target.parentWidth, height: item.parent.box.height });
    await api.call('move_node', { nodeId: item.parent.id, x: item.target.parentX, y: item.target.parentY });
    const root = await api.call('get_node_info', { nodeId: item.root.id });
    const find = findEllipsis(root);
    const text = find.node.absoluteBoundingBox;
    const parent = find.parent.absoluteBoundingBox;
    const right = text.x + text.width;
    const rootRight = root.absoluteBoundingBox.x + root.absoluteBoundingBox.width;
    if (Math.abs(text.x - parent.x) > 0.5 || right > rootRight - 15.5) throw new Error(`${id}: ellipsis frame repair did not preserve the 16px inset`);
    repaired.push({ screen: id, parentId: find.parent.id, textId: find.node.id, parentBox: parent, textBox: text, rightInset: round(rootRight - right) });
  }
  const result = { repairedAt: new Date().toISOString(), channel: backup.channel, count: repaired.length, repaired };
  save('d04-native-ellipsis-frame-repair.json', result);
  console.log(JSON.stringify(result, null, 2));
} finally {
  await api.close();
}
