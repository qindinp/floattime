const { spawn } = require('child_process');
const fs = require('fs');
const path = require('path');
const proj = 'C:/Users/Administrator/.qclaw/workspace/taobao-time-float';

function run(cmd, args, cwd) {
  return new Promise((resolve, reject) => {
    const child = spawn(cmd, args, { cwd, shell: true, stdio: 'inherit' });
    child.on('close', (code) => code === 0 ? resolve() : reject(new Error(`exit ${code}`)));
    child.on('error', reject);
  });
}

async function main() {
  // Step 1: Build
  console.log('=== Building web app ===');
  await run('node', ['node_modules/vite/bin/vite.js', 'build'], proj);

  // Step 2: Add android (if not exists)
  const androidDir = path.join(proj, 'android');
  if (!fs.existsSync(androidDir)) {
    console.log('\n=== Adding Android platform ===');
    await run('node', ['node_modules/@capacitor/cli/bin/capacitor', 'add', 'android'], proj);
  } else {
    console.log('\n=== Android already exists ===');
  }

  // Step 3: Copy
  console.log('\n=== Copying web to Android ===');
  await run('node', ['node_modules/@capacitor/cli/bin/capacitor', 'copy', 'android'], proj);

  // Step 4: Sync
  console.log('\n=== Syncing ===');
  await run('node', ['node_modules/@capacitor/cli/bin/capacitor', 'sync', 'android'], proj);

  console.log('\n✅ 构建完成！APK 路径: android/app/build/outputs/apk/debug/');
}

main().then(() => process.exit(0)).catch(e => {
  console.error('\n❌ 失败:', e.message);
  process.exit(1);
});
