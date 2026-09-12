async page => {
  const pages=[];
  for(const prefix of ['P','M']){
    const total=prefix==='P'?26:14;
    for(let i=1;i<=total;i++){
      const id=prefix+String(i).padStart(2,'0');
      await page.goto(`http://127.0.0.1:8785/prototype.html?qa=${Date.now()}&page=${id}`);
      pages.push({id,badges:await page.locator('#canvas .badge').evaluateAll(nodes=>nodes.map(node=>({text:node.textContent.trim(),tone:node.dataset.tone,domain:node.dataset.domain})))});
    }
  }
  return {pages};
}
