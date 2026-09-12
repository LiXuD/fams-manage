import fs from 'node:fs';
import path from 'node:path';
import { connect, read, save } from './local-figma-client.mjs';

const out = path.resolve('docs/superpowers/designs/2026-09-06-fams-ui/figma-native');
const maps = read('screens-map.json');
const api = await connect();
const sourceFiles = ['M01', 'M02', 'M03', 'M04', 'M05', 'M06', 'M07', 'M08', 'M09', 'M10', 'M11', 'M12', 'M13', 'M14'];
const findEllipsis = (node, parent = null) => {
  if (node.type === 'TEXT' && node.characters === '···') return { node, parent };
  for (const child of node.children || []) {
    const found = findEllipsis(child, node);
    if (found) return found;
  }
  return null;
};
const round = value => Math.round(value * 100) / 100;

try {
  const backup = { capturedAt: new Date().toISOString(), channel: process.env.FAMS_FIGMA_CHANNEL || 'pn1zl5yt', pages: {} };
  for (const id of sourceFiles) {
    const live = await api.call('get_node_info', { nodeId: maps[id].nodeId });
    const found = findEllipsis(live);
    if (!found) throw new Error(`${id}: header ellipsis text was not found`);
    const rootBox = live.absoluteBoundingBox;
    const textBox = found.node.absoluteBoundingBox;
    const parentBox = found.parent.absoluteBoundingBox;
    const targetRight = rootBox.x + rootBox.width - 16;
    const targetX = targetRight - textBox.width;
    const targetLocalX = targetX - parentBox.x;
    const targetLocalY = textBox.y - parentBox.y;
    backup.pages[id] = {
      root: { id: live.id, box: rootBox },
      parent: { id: found.parent.id, name: found.parent.name, box: parentBox },
      text: { id: found.node.id, characters: found.node.characters, box: textBox },
      target: { x: targetLocalX, y: targetLocalY },
    };
  }
  save('d04-native-live-pre-ellipsis-repair.json', backup);

  const repaired = [];
  for (const id of sourceFiles) {
    const item = backup.pages[id];
    await api.call('move_node', { nodeId: item.text.id, x: item.target.x, y: item.target.y });
    const verified = await api.call('get_node_info', { nodeId: item.text.id });
    const right = verified.absoluteBoundingBox.x + verified.absoluteBoundingBox.width;
    const rootRight = item.root.box.x + item.root.box.width;
    if (right > rootRight - 15.5) throw new Error(`${id}: ellipsis still exceeds the 16px right inset`);
    repaired.push({
      screen: id,
      nodeId: verified.id,
      box: verified.absoluteBoundingBox,
      rootRight,
      rightInset: round(rootRight - right),
    });
  }
  const result = { repairedAt: new Date().toISOString(), channel: backup.channel, count: repaired.length, repaired };
  save('d04-native-ellipsis-repair.json', result);
  console.log(JSON.stringify(result, null, 2));
} finally {
  await api.close();
}
