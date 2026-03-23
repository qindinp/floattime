// install_ts.js - 安装 TypeScript 依赖并初始化 Capacitor
// 使用说明: node install_ts.js
//   自动查找项目根目录，无硬编码路径

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
const CAP_BIN = path.join(PROJ, 'node_modules', '@capacitor', 'cli', 'bin', 'capacitor')

function run(cmd, args, cwd) {
  return new Promise((resolve, reject) => {
    const child = spawn(cmd, args, { cwd, shell: true, stdio: 'inherit' })
    child.on('close', code => code === 0 ? resolve() : reject(new Error(`exit ${code}`)))
    child.on('error', reject)
  })
}

function runCap(args) {
  return run('node', [CAP_BIN, ...args], PROJ)
}

async function main() {
  console.log('=== Installing TypeScript ===')
  await run('npm', ['install', 'typescript', '--save-dev'], PROJ)

  console.log('\n=== Init Capacitor ===')
  const androidExists = fs.existsSync(path.join(PROJ, 'android'))
  if (!androidExists) {
    await runCap(['init', 'FloatTime', 'com.floattime.app', '--web-dir=dist'])
    console.log('\n=== Adding Android platform ===')
    await runCap(['add', 'android'])
  }

  console.log('\n=== Copy & Sync ===')
  await runCap(['copy', 'android'])
  await runCap(['sync', 'android'])

  console.log('\n✅ 完成！APK 路径: android/app/build/outputs/apk/debug/')
}

main()
  .then(() => process.exit(0))
  .catch(e => {
    console.error('\n❌ 失败:', e.message)
    process.exit(1)
  })
