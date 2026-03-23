// do_build.js - Web + Android 联合构建脚本
// 使用说明: node do_build.js
//   依赖: node_modules/vite, @capacitor/cli, @capacitor/android, @capacitor/core
//   无需硬编码路径，从项目根目录读取配置

const { spawn } = require('child_process')
const path = require('path')
const fs = require('fs')

// 自动检测项目根目录（向上查找 package.json）
function findProjectRoot(dir) {
  if (fs.existsSync(path.join(dir, 'package.json'))) return dir
  const parent = path.dirname(dir)
  if (parent === dir) throw new Error('找不到 package.json，请从项目根目录运行 node do_build.js')
  return findProjectRoot(parent)
}

const PROJ = findProjectRoot(__dirname)
const VITE_BIN = path.join(PROJ, 'node_modules', 'vite', 'bin', 'vite.js')
const CAP_BIN  = path.join(PROJ, 'node_modules', '@capacitor', 'cli', 'bin', 'capacitor')
const DIST_DIR = path.join(PROJ, 'dist')
const ANDROID_DIR = path.join(PROJ, 'android')

function run(cmd, args, cwd) {
  return new Promise((resolve, reject) => {
    const child = spawn(cmd, args, { cwd, shell: true, stdio: 'inherit' })
    child.on('close', code => code === 0 ? resolve() : reject(new Error(`exit ${code}`)))
    child.on('error', reject)
  })
}

async function main() {
  console.log('=== Step 1: Build web app ===')
  await run('node', [VITE_BIN, 'build'], PROJ)

  console.log('\n=== Step 2: Sync to Android ===')
  const androidExists = fs.existsSync(ANDROID_DIR)
  if (!androidExists) {
    console.log('Android platform not found, skipping Capacitor sync.')
    console.log('提示: 首次运行请先执行 npx cap add android')
  } else {
    await run('node', [CAP_BIN, 'copy', 'android'], PROJ)
    await run('node', [CAP_BIN, 'sync', 'android'], PROJ)
    console.log('\n✅ APK 路径: android/app/build/outputs/apk/debug/')
  }

  console.log('\n✅ Web 构建完成！产物目录: dist/')
}

main()
  .then(() => process.exit(0))
  .catch(e => {
    console.error('\n❌ 失败:', e.message)
    process.exit(1)
  })
