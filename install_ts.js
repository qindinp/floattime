const { spawn } = require('child_process');
const proj = 'C:/Users/Administrator/.qclaw/workspace/taobao-time-float';

const child = spawn('C:/PROGRA~1/nodejs/npm.cmd', ['install', 'typescript', '--save-dev'], {
  cwd: proj, shell: true, stdio: 'inherit'
});
child.on('close', (code) => {
  if (code === 0) {
    // Now run cap init
    const s2 = spawn('node', [proj + '/node_modules/@capacitor/cli/bin/capacitor', 'init', 'FloatTime', 'com.floattime.app', '--web-dir=dist'], {
      cwd: proj, shell: true, stdio: 'inherit'
    });
    s2.on('close', (c2) => {
      if (c2 === 0) {
        const fs = require('fs');
        const exists = fs.existsSync(proj + '/android');
        if (!exists) {
          const s3 = spawn('node', [proj + '/node_modules/@capacitor/cli/bin/capacitor', 'add', 'android'], {
            cwd: proj, shell: true, stdio: 'inherit'
          });
          s3.on('close', (c3) => runCopySync(proj));
        } else {
          runCopySync(proj);
        }
      }
    });
  }
});

function runCopySync(proj) {
  const s4 = spawn('node', [proj + '/node_modules/@capacitor/cli/bin/capacitor', 'copy', 'android'], {
    cwd: proj, shell: true, stdio: 'inherit'
  });
  s4.on('close', (c4) => {
    if (c4 === 0) {
      const s5 = spawn('node', [proj + '/node_modules/@capacitor/cli/bin/capacitor', 'sync', 'android'], {
        cwd: proj, shell: true, stdio: 'inherit'
      });
      s5.on('close', (c5) => {
        console.log('\n=== All done! ===');
        console.log('APK at: android/app/build/outputs/apk/debug/');
      });
    }
  });
}
