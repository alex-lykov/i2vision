/*
 * Copyright (c) 2026. Oleksii Lykov.
 *
 * Licensed under the MIT License.
 * SPDX-License-Identifier: MIT
 */

import * as assert from 'assert';
import {
  parseUnifiedDiff,
  getChangeTypeIcon,
  getChangeTypeLabel,
  getChangeTypeClass,
  DEFAULT_DIFF_DISPLAY_CONFIG,
  DiffCardData,
  DiffFileEntry,
  DiffChangeType,
} from '../../webview/components/DiffCard.types';

suite('DiffCard.types', () => {
  suite('parseUnifiedDiff', () => {
    test('should parse a simple single-file diff', () => {
      const diffText = [
        'diff --git a/file.txt b/file.txt',
        'index abc1234..def5678 100644',
        '--- a/file.txt',
        '+++ b/file.txt',
        '@@ -1,3 +1,4 @@',
        ' line1',
        '+added line',
        ' line2',
        ' line3',
      ].join('\n');

      const data = parseUnifiedDiff(diffText, 'HEAD');

      assert.strictEqual(data.files.length, 1, 'Should have one file');
      assert.strictEqual(data.files[0].filePath, 'file.txt', 'File path should be parsed');
      assert.strictEqual(data.files[0].changeType, 'modified', 'Should be modified');
      assert.strictEqual(data.files[0].additions, 1, 'Should have 1 addition');
      assert.strictEqual(data.files[0].deletions, 0, 'Should have 0 deletions');
      assert.strictEqual(data.files[0].hunks.length, 1, 'Should have 1 hunk');
      assert.strictEqual(data.totalAdditions, 1);
      assert.strictEqual(data.totalDeletions, 0);
      assert.strictEqual(data.totalFiles, 1);
    });

    test('should parse a diff with additions and deletions', () => {
      const diffText = [
        'diff --git a/app.ts b/app.ts',
        'index 111..222 100644',
        '--- a/app.ts',
        '+++ b/app.ts',
        '@@ -10,5 +10,5 @@',
        ' context line',
        '-removed line',
        '+added line',
        ' another context',
      ].join('\n');

      const data = parseUnifiedDiff(diffText, 'main');

      assert.strictEqual(data.files[0].additions, 1);
      assert.strictEqual(data.files[0].deletions, 1);
      assert.strictEqual(data.totalAdditions, 1);
      assert.strictEqual(data.totalDeletions, 1);
    });

    test('should parse a new file diff', () => {
      const diffText = [
        'diff --git a/newfile.ts b/newfile.ts',
        'new file mode 100644',
        'index 0000000..abc1234',
        '--- /dev/null',
        '+++ b/newfile.ts',
        '@@ -0,0 +1,3 @@',
        '+line1',
        '+line2',
        '+line3',
      ].join('\n');

      const data = parseUnifiedDiff(diffText);

      assert.strictEqual(data.files[0].changeType, 'added');
      assert.strictEqual(data.files[0].additions, 3);
      assert.strictEqual(data.files[0].deletions, 0);
    });

    test('should parse a deleted file diff', () => {
      const diffText = [
        'diff --git a/old.ts b/old.ts',
        'deleted file mode 100644',
        'index abc1234..0000000',
        '--- a/old.ts',
        '+++ /dev/null',
        '@@ -1,2 +0,0 @@',
        '-line1',
        '-line2',
      ].join('\n');

      const data = parseUnifiedDiff(diffText);

      assert.strictEqual(data.files[0].changeType, 'removed');
      assert.strictEqual(data.files[0].deletions, 2);
    });

    test('should parse a renamed file diff', () => {
      const diffText = [
        'diff --git a/oldname.ts b/newname.ts',
        'rename from oldname.ts',
        'rename to newname.ts',
        'index abc..def 100644',
        '--- a/oldname.ts',
        '+++ b/newname.ts',
        '@@ -1,2 +1,2 @@',
        ' context',
        '-old line',
        '+new line',
      ].join('\n');

      const data = parseUnifiedDiff(diffText);

      assert.strictEqual(data.files[0].changeType, 'renamed');
      assert.strictEqual(data.files[0].oldFilePath, 'oldname.ts');
      assert.strictEqual(data.files[0].filePath, 'newname.ts');
    });

    test('should parse multiple files in a single diff', () => {
      const diffText = [
        'diff --git a/file1.ts b/file1.ts',
        'index 111..222 100644',
        '--- a/file1.ts',
        '+++ b/file1.ts',
        '@@ -1,2 +1,3 @@',
        ' line1',
        '+added',
        ' line2',
        'diff --git a/file2.ts b/file2.ts',
        'index 333..444 100644',
        '--- a/file2.ts',
        '+++ b/file2.ts',
        '@@ -1,3 +1,2 @@',
        ' line1',
        '-removed',
        ' line2',
      ].join('\n');

      const data = parseUnifiedDiff(diffText);

      assert.strictEqual(data.files.length, 2, 'Should have 2 files');
      assert.strictEqual(data.totalFiles, 2);
      assert.strictEqual(data.totalAdditions, 1);
      assert.strictEqual(data.totalDeletions, 1);
    });

    test('should parse binary file diff', () => {
      const diffText = [
        'diff --git a/image.png b/image.png',
        'index abc..def 100644',
        'Binary files a/image.png and b/image.png differ',
      ].join('\n');

      const data = parseUnifiedDiff(diffText);

      assert.strictEqual(data.files[0].isBinary, true);
    });

    test('should parse hunk header with line numbers', () => {
      const diffText = [
        'diff --git a/test.ts b/test.ts',
        'index abc..def 100644',
        '--- a/test.ts',
        '+++ b/test.ts',
        '@@ -5,3 +5,4 @@',
        ' context',
        '+added',
        ' context2',
      ].join('\n');

      const data = parseUnifiedDiff(diffText);

      assert.strictEqual(data.files[0].hunks[0].oldStart, 5);
      assert.strictEqual(data.files[0].hunks[0].oldLines, 3);
      assert.strictEqual(data.files[0].hunks[0].newStart, 5);
      assert.strictEqual(data.files[0].hunks[0].newLines, 4);
    });

    test('should handle empty diff', () => {
      const data = parseUnifiedDiff('', 'HEAD');

      assert.strictEqual(data.files.length, 0);
      assert.strictEqual(data.totalAdditions, 0);
      assert.strictEqual(data.totalDeletions, 0);
      assert.strictEqual(data.totalFiles, 0);
    });

    test('should merge display config with defaults', () => {
      const data = parseUnifiedDiff('', 'HEAD', { showLineNumbers: false, maxLinesPerHunk: 100 });

      assert.strictEqual(data.display.showLineNumbers, false);
      assert.strictEqual(data.display.maxLinesPerHunk, 100);
      // Defaults should be preserved for unspecified fields
      assert.strictEqual(data.display.collapseContext, DEFAULT_DIFF_DISPLAY_CONFIG.collapseContext);
      assert.strictEqual(data.display.contextLines, DEFAULT_DIFF_DISPLAY_CONFIG.contextLines);
    });

    test('should assign line numbers to diff lines', () => {
      const diffText = [
        'diff --git a/a.ts b/a.ts',
        'index abc..def 100644',
        '--- a/a.ts',
        '+++ b/a.ts',
        '@@ -1,4 +1,4 @@',
        ' context1',
        '-removed',
        '+added',
        ' context2',
      ].join('\n');

      const data = parseUnifiedDiff(diffText);
      const hunk = data.files[0].hunks[0];

      // Context line should have both line numbers
      assert.strictEqual(hunk.lines[0].type, 'context');
      assert.strictEqual(hunk.lines[0].oldLineNumber, 1);
      assert.strictEqual(hunk.lines[0].newLineNumber, 1);

      // Removed line should have old line number
      assert.strictEqual(hunk.lines[1].type, 'removed');
      assert.strictEqual(hunk.lines[1].oldLineNumber, 2);

      // Added line should have new line number
      assert.strictEqual(hunk.lines[2].type, 'added');
      assert.strictEqual(hunk.lines[2].newLineNumber, 2);
    });
  });

  suite('getChangeTypeIcon', () => {
    test('should return correct icons', () => {
      assert.strictEqual(getChangeTypeIcon('added'), '🟢');
      assert.strictEqual(getChangeTypeIcon('removed'), '🔴');
      assert.strictEqual(getChangeTypeIcon('modified'), '🟡');
      assert.strictEqual(getChangeTypeIcon('renamed'), '🔄');
    });
  });

  suite('getChangeTypeLabel', () => {
    test('should return correct labels', () => {
      assert.strictEqual(getChangeTypeLabel('added'), 'Added');
      assert.strictEqual(getChangeTypeLabel('removed'), 'Removed');
      assert.strictEqual(getChangeTypeLabel('modified'), 'Modified');
      assert.strictEqual(getChangeTypeLabel('renamed'), 'Renamed');
    });
  });

  suite('getChangeTypeClass', () => {
    test('should return correct CSS classes', () => {
      assert.strictEqual(getChangeTypeClass('added'), 'diff-file-added');
      assert.strictEqual(getChangeTypeClass('removed'), 'diff-file-removed');
      assert.strictEqual(getChangeTypeClass('modified'), 'diff-file-modified');
      assert.strictEqual(getChangeTypeClass('renamed'), 'diff-file-renamed');
    });
  });
});