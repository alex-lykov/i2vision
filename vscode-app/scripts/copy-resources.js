const fs = require('fs');
const path = require('path');

// Copy default-agent-config.yaml
const srcDir = path.join(__dirname, '..', 'conf-agent-core', 'src', 'commonMain', 'resources');
const destDir = path.join(__dirname, '..', 'out', 'conf-agent-core', 'src', 'commonMain', 'resources');

if (!fs.existsSync(destDir)) {
    fs.mkdirSync(destDir, { recursive: true });
}

const srcFile = path.join(srcDir, 'default-agent-config.yaml');
const destFile = path.join(destDir, 'default-agent-config.yaml');

if (fs.existsSync(srcFile)) {
    fs.copyFileSync(srcFile, destFile);
    console.log('Copied default-agent-config.yaml to out directory');
} else {
    console.error('ERROR: default-agent-config.yaml not found at:', srcFile);
    process.exit(1);
}

// Copy webview.js to out/resources
const webviewSrc = path.join(__dirname, '..', 'resources', 'webview.js');
const webviewDestDir = path.join(__dirname, '..', 'out', 'resources');
const webviewDest = path.join(webviewDestDir, 'webview.js');

if (!fs.existsSync(webviewDestDir)) {
    fs.mkdirSync(webviewDestDir, { recursive: true });
}

if (fs.existsSync(webviewSrc)) {
    fs.copyFileSync(webviewSrc, webviewDest);
    console.log('Copied webview.js to out/resources directory');
} else {
    console.error('WARNING: webview.js not found at:', webviewSrc);
}
