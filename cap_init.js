const { spawn } = require('child_process');
const path = require('path');
const fs = require('fs');

const proj = 'C:/Users/Administrator/.qclaw/workspace/taobao-time-float';

// Run a command and wait
function run(cmd, args, cwd) {
  return new Promise((resolve, reject) => {
    const child = spawn(cmd, args, { cwd, shell: true, stdio: 'inherit' });
    child.on('close', (code) => {
      if (code === 0) resolve(code);
      else reject(new Error(`${cmd} ${args.join(' ')} exited with code ${code}`));
    });
    child.on('error', reject);
  });
}

async function main() {
  console.log('=== Step 1: Build web app ===');
  await run('node', ['node_modules/vite/bin/vite.js', 'build'], proj);

  console.log('\n=== Step 2: Init Capacitor ===');
  await run('node', ['node_modules/@capacitor/cli/bin/capacitor', 'init', 'FloatTime', 'com.floattime.app', '--web-dir=dist'], proj);

  console.log('\n=== Step 3: Add Android ===');
  // Check if android already exists
  const androidExists = fs.existsSync(path.join(proj, 'android'));
  if (!androidExists) {
    await run('node', ['node_modules/@capacitor/cli/bin/capacitor', 'add', 'android'], proj);
  } else {
    console.log('Android platform already exists, skipping add');
  }

  console.log('\n=== Step 4: Copy web to Android ===');
  await run('node', ['node_modules/@capacitor/cli/bin/capacitor', 'copy', 'android'], proj);

  console.log('\n=== Step 5: Sync ===');
  await run('node', ['node_modules/@capacitor/cli/bin/capacitor', 'sync', 'android'], proj);

  console.log('\n=== Done! ===');
  console.log('APK location: android/app/build/outputs/apk/debug/');
}

main().then(() => process.exit(0)).catch(e => {
  console.error('ERROR:', e.message);
  process.exit(1);
});
