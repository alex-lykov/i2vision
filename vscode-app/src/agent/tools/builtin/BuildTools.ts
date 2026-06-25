import { ToolDefinition } from '../ToolTypes';

/**
 * Build and compilation tools
 */
export const buildTools: ToolDefinition[] = [
  {
    name: 'run_build',
    description: 'Run a build command. FOR COMPILATION: use compileKotlin (source only, NO tests). FOR TESTS: use test. NEVER use "build" - it runs ALL tests and is slow.',
    category: 'build',
    isReadOnly: false,
    requiresConfirmation: false,
    parameters: {
      type: 'object',
      properties: {
        command: {
          type: 'string',
          description: 'Build command. FOR COMPILATION (source only): ./gradlew compileKotlin. FOR TESTS: ./gradlew test. NEVER use ./gradlew build (runs all tests, slow).',
          enum: [
            './gradlew compileKotlin',
            './gradlew :app:server:compileKotlin',
            './gradlew :app:shared:compileKotlin',
            './gradlew :app:client:compileKotlin',
            './gradlew test',
            './gradlew :app:server:test',
            'gradlew.bat compileKotlin',
            'gradlew.bat :app:server:compileKotlin',
            'gradlew.bat test',
            'npm run build',
            'npm test',
            'tsc',
            'mvn clean install',
            'mvn test'
          ]
        }
      },
      required: ['command']
    },
    timeoutMs: 120000,
    async handler(args, ctx) {
      let command = args.command;
      const timeout = 120000;
      
      // Windows path fix
      if (process.platform === 'win32' && /^\.\//i.test(command)) {
        command = command.replace(/^\.\//, '.\\');
        ctx.log(`Windows PowerShell fix: ./ -> .\\`);
      }
      
      // Run build command
      const result = await ctx.runCommand(command, timeout);
      
      const output = (result.stdout || '') + '\n' + (result.stderr || '');
      const exitCodeInfo = result.exitCode !== null ? ` (exit: ${result.exitCode})` : '';
      const hasFailure = result.exitCode !== 0 || 
        output.includes('BUILD FAILED') || 
        output.includes('FAILED') || 
        output.includes('error:');
      
      if (!hasFailure) {
        return { 
          result: `✅ BUILD SUCCESSFUL${exitCodeInfo}\n\nCompilation passed.\n\n${output.slice(-500)}` 
        };
      }
      
      // Extract errors from output
      const errors = extractCompilationErrors(output);
      
      return { 
        result: `❌ BUILD FAILED\n\nExit code: ${result.exitCode}\n\n${errors}\n\n⚠️ DO NOT re-run build. READ files above, FIX errors, THEN re-run.`, 
        error: 'Build failed' 
      };
    }
  }
];

/**
 * Extract compilation errors from build output
 */
function extractCompilationErrors(output: string): string {
  if (!output) return 'No error output';
  
  const errors: string[] = [];
  
  // Kotlin error pattern: e: file:///path:line:col message
  const kotlinErrorPattern = /e:\s*file:\/\/\/?([a-zA-Z]:[\\/].+?):(\d+):(\d+)\s+(.+)/g;
  let match;
  
  while ((match = kotlinErrorPattern.exec(output)) !== null) {
    const [, filePath, lineNum, col, message] = match;
    const normalizedPath = filePath.replace(/\\/g, '/');
    errors.push(`${normalizedPath}:${lineNum}:${col} ${message}`);
  }
  
  // Unresolved reference errors
  const unresolvedErrors = output.match(/Unresolved reference[^\n]+/g);
  if (unresolvedErrors) {
    unresolvedErrors.forEach(err => errors.push(err.trim()));
  }
  
  // Other common error patterns
  const keywordPatterns = [
    /Type mismatch[^\n]+/g,
    /is not abstract[^\n]+/g,
    /must implement[^\n]+/g,
    /cannot find symbol[^\n]+/g,
    /Overload resolution[^\n]+/g,
    /Conflicting overloads[^\n]+/g
  ];
  
  for (const pattern of keywordPatterns) {
    const matches = output.match(pattern);
    if (matches) {
      matches.forEach(err => errors.push(err.trim()));
    }
  }
  
  // FAILED context lines
  const lines = output.split('\n');
  for (let i = 0; i < lines.length; i++) {
    const line = lines[i];
    if (line.includes('FAILED') && !line.includes('BUILD FAILED')) {
      const contextLines = [];
      for (let j = i; j < Math.min(i + 4, lines.length); j++) {
        const contextLine = lines[j].trim();
        if (contextLine && !contextLine.startsWith('> Task') && contextLine.length > 10) {
          contextLines.push(contextLine);
        }
      }
      if (contextLines.length > 0) {
        errors.push(contextLines.slice(0, 3).join(' '));
      }
    }
  }
  
  // Format result
  let result = '=== COMPILATION ERRORS ===\n\n';
  
  if (errors.length > 0) {
    result += errors.slice(0, 20).join('\n\n');
    if (errors.length > 20) {
      result += `\n\n... and ${errors.length - 20} more errors`;
    }
  } else {
    const failedLines = output.split('\n').filter(l => l.includes('FAILED') || l.includes('error:'));
    result += failedLines.slice(0, 10).join('\n') || 'Build failed with unknown error';
  }
  
  // FILES TO READ section
  const mentionedFiles = new Set<string>();
  const filePattern = /([a-zA-Z]:[\\/].+?\.(kt|java|ts|js|py|rs|go|cpp|hpp|cc|h))|([a-zA-Z0-9_\-\/]+\.kt)/gi;
  let fileMatch;
  
  while ((fileMatch = filePattern.exec(output)) !== null) {
    const path = fileMatch[0].replace(/\\/g, '/');
    mentionedFiles.add(path);
  }
  
  if (mentionedFiles.size > 0) {
    result += `\n\n=== FILES TO READ AND FIX ===\n`;
    result += Array.from(mentionedFiles).slice(0, 10).map(f => `  - ${f}`).join('\n');
  }
  
  return result;
}
