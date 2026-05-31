const fs = require('fs');
const path = require('path');

const srcDir = path.join(__dirname, '..', 'conf-agent-core', 'src', 'commonMain', 'resources');
const destDir = path.join(__dirname, '..', 'out', 'conf-agent-core', 'src', 'commonMain', 'resources');

// Create destination directory if it doesn't exist
if (!fs.existsSync(destDir)) {
    fs.mkdirSync(destDir, { recursive: true });
}

// Copy default-agent-config.yaml
const srcFile = path.join(srcDir, 'default-agent-config.yaml');
const destFile = path.join(destDir, 'default-agent-config.yaml');

if (fs.existsSync(srcFile)) {
    fs.copyFileSync(srcFile, destFile);
    console.log('Copied default-agent-config.yaml to out directory');
} else {
    console.error('ERROR: default-agent-config.yaml not found at:', srcFile);
    process.exit(1);
}
