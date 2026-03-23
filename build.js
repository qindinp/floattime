// build.js - 仅构建 Web 产物（不依赖 Capacitor）
// 使用说明: node build.js
//   等同于: npx vite build
//   适用于只想更新 web dist 的场景

const { spawn } = require('child_process')
const path = require('path')
const fs = require('fs')

function findProjectRoot(dir) {
  if (fs.existsSync(path.join(dir, 'package.json'))) return dir
  const parent = path.dirname(dir)
  if (parent === dir) throw new Error('找不到 package.json')
  return findProjectRoot(parent)
}

const PROJ = findProjectRoot(__dirname)
const VITE_BIN = path.join(PROJ, 'node_modules', 'vite', 'bin', 'vite.js')

function run(cmd, args, cwd) {
  return new Promise((resolve, reject) => {
    const child = spawn(cmd, args, { cwd, shell: true, stdio: 'inherit' })
    child.on('close', code => code === 0 ? resolve() : reject(new Error(`exit ${code}`)))
    child.on('error', reject)
  })
}

main()
  .then(() => {
    console.log('\n✅ Web 构建完成！产物目录: dist/')
    process.exit(0)
  })
  .catch(e => {
    console.error('\n❌ 构建失败:', e.message)
    process.exit(1)
  })

async function main() {
  console.log('Building with Vite...')
  await run('node', [VITE_BIN, 'build'], PROJ)
}
