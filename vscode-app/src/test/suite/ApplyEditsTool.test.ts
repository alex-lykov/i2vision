/**
 * Tests for ApplyEditsTool - Structured batch editing
 */

import * as assert from 'assert';
import {
  applyEditsToContent,
  validateEdits,
  getContextAroundLine,
  EditOperation
} from '../../agent/ApplyEditsTool';

suite('ApplyEditsTool', () => {
  test('should apply single edit successfully', () => {
    const content = `function hello() {
  console.log("world");
}`;
    
    const edits: EditOperation[] = [
      {
        search: 'console.log("world");',
        replace: 'console.log("universe");'
      }
    ];
    
    const result = applyEditsToContent(content, edits);
    
    assert.strictEqual(result.appliedCount, 1);
    assert.strictEqual(result.totalCount, 1);
    assert.strictEqual(result.failures.length, 0);
    assert.ok(result.finalContent.includes('console.log("universe");'));
  });

  test('should apply multiple edits in sequence', () => {
    const content = `const x = 1;
const y = 2;
const z = 3;`;
    
    const edits: EditOperation[] = [
      { search: 'const x = 1;', replace: 'const x = 10;' },
      { search: 'const y = 2;', replace: 'const y = 20;' }
    ];
    
    const result = applyEditsToContent(content, edits);
    
    assert.strictEqual(result.appliedCount, 2);
    assert.strictEqual(result.totalCount, 2);
    assert.strictEqual(result.failures.length, 0);
    assert.ok(result.finalContent.includes('const x = 10;'));
    assert.ok(result.finalContent.includes('const y = 20;'));
  });

  test('should fail when search text not found', () => {
    const content = `function test() {
  return true;
}`;
    
    const edits: EditOperation[] = [
      {
        search: 'console.log("missing");',
        replace: 'console.log("found");'
      }
    ];
    
    const result = applyEditsToContent(content, edits);
    
    assert.strictEqual(result.appliedCount, 0);
    assert.strictEqual(result.totalCount, 1);
    assert.strictEqual(result.failures.length, 1);
    assert.strictEqual(result.failures[0].reason, 'NOT_FOUND');
  });

  test('should fail when search text found multiple times', () => {
    const content = `console.log("a");
console.log("a");
console.log("b");`;
    
    const edits: EditOperation[] = [
      {
        search: 'console.log("a");',
        replace: 'console.log("c");'
      }
    ];
    
    const result = applyEditsToContent(content, edits);
    
    assert.strictEqual(result.appliedCount, 0);
    assert.strictEqual(result.totalCount, 1);
    assert.strictEqual(result.failures.length, 1);
    assert.strictEqual(result.failures[0].reason, 'MULTIPLE_MATCHES');
    assert.strictEqual(result.failures[0].occurrences, 2);
  });

  test('should continue applying edits after a failure', () => {
    const content = `const a = 1;
const b = 2;
const c = 3;`;
    
    const edits: EditOperation[] = [
      { search: 'const a = 1;', replace: 'const a = 10;' },
      { search: 'const missing = 0;', replace: 'const missing = 1;' }, // Will fail
      { search: 'const c = 3;', replace: 'const c = 30;' }
    ];
    
    const result = applyEditsToContent(content, edits);
    
    assert.strictEqual(result.appliedCount, 2);
    assert.strictEqual(result.totalCount, 3);
    assert.strictEqual(result.failures.length, 1);
    assert.ok(result.finalContent.includes('const a = 10;'));
    assert.ok(result.finalContent.includes('const c = 30;'));
    assert.ok(!result.finalContent.includes('const missing'));
  });

  test('should validate edits before applying', () => {
    const content = `const x = 1;`;
    
    const edits: EditOperation[] = [
      { search: 'const x = 1;', replace: 'const x = 2;' },
      { search: 'const missing;', replace: 'const found;' }
    ];
    
    const validation = validateEdits(content, edits);
    
    assert.strictEqual(validation.valid, false);
    assert.strictEqual(validation.warnings.length, 1);
    assert.ok(validation.warnings[0].includes('not found'));
  });

  test('should detect no-op edits', () => {
    const content = `const x = 1;`;
    
    const edits: EditOperation[] = [
      { search: 'const x = 1;', replace: 'const x = 1;' }
    ];
    
    const validation = validateEdits(content, edits);
    
    assert.strictEqual(validation.valid, false);
    assert.strictEqual(validation.warnings.length, 1);
    assert.ok(validation.warnings[0].includes('no-op'));
  });

  test('should get context around line number', () => {
    const content = `line 1
line 2
line 3
line 4
line 5`;
    
    const context = getContextAroundLine(content, 3, 1);
    
    assert.ok(context.includes('line 2'));
    assert.ok(context.includes('line 3'));
    assert.ok(context.includes('line 4'));
    assert.ok(context.includes('2:'));
    assert.ok(context.includes('3:'));
    assert.ok(context.includes('4:'));
  });

  test('should handle edge case: empty content', () => {
    const content = '';
    const edits: EditOperation[] = [
      { search: 'test', replace: 'result' }
    ];
    
    const result = applyEditsToContent(content, edits);
    
    assert.strictEqual(result.appliedCount, 0);
    assert.strictEqual(result.failures.length, 1);
    assert.strictEqual(result.failures[0].reason, 'NOT_FOUND');
  });

  test('should handle edge case: empty search string', () => {
    const content = `test content`;
    const edits: EditOperation[] = [
      { search: '', replace: 'result' }
    ];
    
    const result = applyEditsToContent(content, edits);
    
    assert.strictEqual(result.appliedCount, 0);
    assert.strictEqual(result.failures.length, 1);
  });

  test('should preserve indentation in replacements', () => {
    const content = `function test() {
    if (true) {
        console.log("before");
    }
}`;
    
    const edits: EditOperation[] = [
      {
        search: '        console.log("before");',
        replace: '        console.log("after");'
      }
    ];
    
    const result = applyEditsToContent(content, edits);
    
    assert.strictEqual(result.appliedCount, 1);
    assert.ok(result.finalContent.includes('console.log("after");'));
    assert.ok(result.finalContent.includes('    if (true)'));
  });
});
