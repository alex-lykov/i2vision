/*
 * Copyright (c) 2026. Oleksii Lykov.
 *
 * Licensed under the MIT License.
 * SPDX-License-Identifier: MIT
 */

/**
 * Binary File Detector
 * 
 * Detects binary files to prevent garbled text in LLM context
 * and reduce token waste from non-text content.
 */

import * as fs from 'fs';

export class BinaryFileDetector {
  private static readonly CHECK_SIZE = 8192; // Check first 8KB
  private static readonly NULL_BYTE_THRESHOLD = 1; // Any null byte = binary
  
  /**
   * Check if a file is binary
   * @param filePath - Path to the file
   * @returns true if file is binary, false if text
   */
  static async isBinaryFile(filePath: string): Promise<boolean> {
    try {
      const buffer = await fs.promises.readFile(filePath, { encoding: null });
      
      // Empty file is not binary
      if (buffer.length === 0) {
        return false;
      }
      
      // Check for null bytes in first 8KB
      let nullByteCount = 0;
      const checkSize = Math.min(BinaryFileDetector.CHECK_SIZE, buffer.length);
      
      for (let i = 0; i < checkSize; i++) {
        if (buffer[i] === 0) {
          nullByteCount++;
          if (nullByteCount >= BinaryFileDetector.NULL_BYTE_THRESHOLD) {
            return true;
          }
        }
      }
      
      // Check for high ratio of non-printable characters
      let nonPrintableCount = 0;
      for (let i = 0; i < checkSize; i++) {
        const byte = buffer[i];
        // Printable ASCII: 32-126, plus common control chars (9=tab, 10=LF, 13=CR)
        if (byte < 32 && byte !== 9 && byte !== 10 && byte !== 13) {
          nonPrintableCount++;
        }
      }
      
      // If >30% non-printable, likely binary
      const nonPrintableRatio = nonPrintableCount / checkSize;
      return nonPrintableRatio > 0.3;
      
    } catch (error: any) {
      // If we can't read the file, assume it's not binary (safe default)
      console.warn(`BinaryFileDetector: Cannot read ${filePath}: ${error.message}`);
      return false;
    }
  }
  
  /**
   * Synchronous version for quick checks
   */
  static isBinaryFileSync(filePath: string): boolean {
    try {
      const buffer = fs.readFileSync(filePath, { encoding: null });
      
      if (buffer.length === 0) {
        return false;
      }
      
      const checkSize = Math.min(BinaryFileDetector.CHECK_SIZE, buffer.length);
      let nullByteCount = 0;
      let nonPrintableCount = 0;
      
      for (let i = 0; i < checkSize; i++) {
        const byte = buffer[i];
        
        if (byte === 0) {
          nullByteCount++;
          if (nullByteCount >= BinaryFileDetector.NULL_BYTE_THRESHOLD) {
            return true;
          }
        }
        
        if (byte < 32 && byte !== 9 && byte !== 10 && byte !== 13) {
          nonPrintableCount++;
        }
      }
      
      const nonPrintableRatio = nonPrintableCount / checkSize;
      return nonPrintableRatio > 0.3;
      
    } catch (error: any) {
      console.warn(`BinaryFileDetector: Cannot read ${filePath}: ${error.message}`);
      return false;
    }
  }
  
  /**
   * Get file extension-based binary detection (fast, less accurate)
   * Use as a quick pre-check before full binary detection
   */
  static isLikelyBinaryByExtension(filePath: string): boolean {
    const ext = filePath.toLowerCase().split('.').pop() || '';
    
    const binaryExtensions = [
      // Images
      'png', 'jpg', 'jpeg', 'gif', 'bmp', 'svg', 'webp', 'ico',
      // Audio/Video
      'mp3', 'mp4', 'avi', 'mov', 'wav', 'flac', 'ogg', 'webm',
      // Documents
      'pdf', 'doc', 'docx', 'xls', 'xlsx', 'ppt', 'pptx', 'odt',
      // Archives
      'zip', 'tar', 'gz', 'rar', '7z', 'bz2', 'xz',
      // Compiled
      'class', 'jar', 'war', 'ear', 'so', 'dll', 'dylib', 'exe', 'bin',
      // Other binary
      'db', 'sqlite', 'mdb', 'lock', 'pid', 'pkl', 'pickle', 'parquet',
      // Fonts
      'ttf', 'otf', 'woff', 'woff2', 'eot',
      // Certificates/Keys
      'crt', 'pem', 'key', 'p12', 'pfx', 'jks'
    ];
    
    return binaryExtensions.includes(ext);
  }
  
  /**
   * Get list of known text extensions (for whitelisting)
   */
  static isKnownTextExtension(filePath: string): boolean {
    const ext = filePath.toLowerCase().split('.').pop() || '';
    
    const textExtensions = [
      // Source code
      'ts', 'tsx', 'js', 'jsx', 'kt', 'kts', 'java', 'py', 'go', 'rs', 'cpp', 'c', 'h', 'hpp',
      'cs', 'vb', 'php', 'rb', 'swift', 'scala', 'clj', 'erl', 'hs', 'ml', 'r', 'm', 'mm',
      // Web
      'html', 'htm', 'css', 'scss', 'sass', 'less', 'vue', 'svelte',
      // Config/Data
      'json', 'yaml', 'yml', 'toml', 'xml', 'ini', 'cfg', 'conf', 'properties',
      'md', 'markdown', 'rst', 'adoc', 'txt', 'text',
      // Build/Scripts
      'gradle', 'gradlew', 'mvnw', 'pom', 'makefile', 'mk', 'sh', 'bash', 'bat', 'cmd', 'ps1',
      // SQL/NoSQL
      'sql', 'graphql', 'gql',
      // Other
      'log', 'env', 'gitignore', 'dockerignore', 'editorconfig'
    ];
    
    return textExtensions.includes(ext);
  }
}
