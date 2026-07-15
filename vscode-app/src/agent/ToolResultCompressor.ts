/**
 * ToolResultCompressor - Proactively compress large tool results before sending to LLM.
 *
 * The 3D LLM proxy truncates large results. This module compresses tool outputs
 * to stay within limits while preserving critical information.
 */

export interface CompressionResult {
  compressed: string;
  wasCompressed: boolean;
  originalLength: number;
  compressedLength: number;
  technique: string;
}

export class ToolResultCompressor {
  private maxChars: number;
  private maxLines: number;

  constructor(maxChars: number = 8000, maxLines: number = 200) {
    this.maxChars = maxChars;
    this.maxLines = maxLines;
  }

  configure(maxChars: number, maxLines?: number): void {
    this.maxChars = maxChars;
    if (maxLines !== undefined) this.maxLines = maxLines;
  }

  compress(result: string): CompressionResult {
    if (!result || result.length <= this.maxChars) {
      return {
        compressed: result,
        wasCompressed: false,
        originalLength: result?.length || 0,
        compressedLength: result?.length || 0,
        technique: 'none',
      };
    }

    // Try structured compression first
    const treeCompressed = this.compressTreeListing(result);
    if (treeCompressed.wasCompressed) return treeCompressed;

    const searchCompressed = this.compressSearchResult(result);
    if (searchCompressed.wasCompressed) return searchCompressed;

    const fileCompressed = this.compressFileContent(result);
    if (fileCompressed.wasCompressed) return fileCompressed;

    // Fallback: line truncation
    return this.truncateLines(result);
  }

  private compressTreeListing(result: string): CompressionResult {
    const isTree = result.includes('├──') || result.includes('└──') || result.includes('📁');
    if (!isTree) {
      return this.noop(result);
    }

    const lines = result.split('\n');
    const structureLines = lines.filter(
      (l) => l.includes('──') || l.includes('📁') || l.includes('📄')
    );

    if (structureLines.length === 0) return this.noop(result);

    const header = lines.find((l) => l.includes('Directory') || l.includes('Tree')) || '';
    const kept = structureLines.slice(0, this.maxLines);
    const omitted = structureLines.length - kept.length;

    const compressed = [
      header,
      ...kept,
      omitted > 0 ? `\n[... ${omitted} more entries omitted, total: ${structureLines.length}]` : '',
    ].join('\n');

    return {
      compressed,
      wasCompressed: compressed.length < result.length,
      originalLength: result.length,
      compressedLength: compressed.length,
      technique: 'tree',
    };
  }

  private compressSearchResult(result: string): CompressionResult {
    const match = result.match(/Found\s+(\d+)\s+file\(s\)/i);
    if (!match) return this.noop(result);

    const lines = result.split('\n');
    const fileLines = lines.filter((l) => /^\s*[\/.]/.test(l) || l.match(/:\d+:/));
    const kept = fileLines.slice(0, this.maxLines);
    const omitted = fileLines.length - kept.length;

    const compressed = [
      `Found ${match[1]} file(s):`,
      ...kept,
      omitted > 0 ? `\n[... ${omitted} more matches omitted]` : '',
    ].join('\n');

    return {
      compressed,
      wasCompressed: compressed.length < result.length,
      originalLength: result.length,
      compressedLength: compressed.length,
      technique: 'search',
    };
  }

  private compressFileContent(result: string): CompressionResult {
    // Detect if this is a source file with line numbers
    const hasLineNumbers = /^\s*\d+[:|\s]/.test(result.split('\n')[0] || '');
    if (!hasLineNumbers && result.length < this.maxChars * 2) {
      return this.noop(result);
    }

    const lines = result.split('\n');
    if (lines.length <= this.maxLines) return this.noop(result);

    // Keep first N/2 and last N/2 lines with ellipsis
    const half = Math.floor(this.maxLines / 2);
    const head = lines.slice(0, half);
    const tail = lines.slice(-half);
    const omitted = lines.length - head.length - tail.length;

    const compressed = [
      ...head,
      `\n[... ${omitted} lines omitted (total ${lines.length})]`,
      ...tail,
    ].join('\n');

    return {
      compressed,
      wasCompressed: true,
      originalLength: result.length,
      compressedLength: compressed.length,
      technique: 'file',
    };
  }

  private truncateLines(result: string): CompressionResult {
    const lines = result.split('\n');
    if (lines.length <= this.maxLines && result.length <= this.maxChars) {
      return this.noop(result);
    }

    const truncated = lines.slice(0, this.maxLines).join('\n');
    const finalText =
      truncated.length > this.maxChars
        ? truncated.substring(0, this.maxChars)
        : truncated;

    const omittedLines = lines.length - this.maxLines;
    const omittedChars = result.length - finalText.length;

    const suffix = [
      omittedLines > 0 ? `\n[... ${omittedLines} lines omitted]` : '',
      omittedChars > 0 ? `\n[Original: ${result.length} chars, truncated to ${this.maxChars}]` : '',
    ].join('');

    return {
      compressed: finalText + suffix,
      wasCompressed: true,
      originalLength: result.length,
      compressedLength: finalText.length + suffix.length,
      technique: 'truncate',
    };
  }

  private noop(result: string): CompressionResult {
    return {
      compressed: result,
      wasCompressed: false,
      originalLength: result.length,
      compressedLength: result.length,
      technique: 'none',
    };
  }
}
