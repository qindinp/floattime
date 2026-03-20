// build.js - 简单复制 dist 文件（无需 vite）
const fs = require('fs');
const path = require('path');

function mkdirp(dir) {
  try { fs.mkdirSync(dir, { recursive: true }); } catch(e) {}
}

// dist 已经是最终产物，直接确认存在即可
const distIndex = path.join(__dirname, 'dist', 'index.html');
if (!fs.existsSync(distIndex)) {
  console.error('ERROR: dist/index.html not found!');
  process.exit(1);
}

console.log('Build OK: dist/index.html exists (' + fs.statSync(distIndex).size + ' bytes)');
