// End-to-end test of the agent flow
// This mimics exactly what AgentBridge does

const MODEL_ID = 'minimax-m2.1:cloud';

async function testAgentFlow() {
  console.log('=== Testing Agent Flow ===\n');
  
  // Step 1: Build system prompt (from coding-agent.yaml)
  const systemPrompt = `You are a coding agent specialized in Kotlin development.
You have access to the i2vision codebase context and can read/write files.

When given a task:
1. Use i2vision_get_context to understand the current code architecture
2. Read relevant files before making changes
3. Make focused, minimal edits
4. Verify your changes make sense in the broader architecture

Available context:
- Project: i2-vision
- Current file: 
- Task: List files in the project

--- OUTPUT FORMAT (STRICT) ---
You MUST use this exact format:

Example 1 (with tool):
reasoning: I need to read the file to understand its contents.
tool_call: {"tool":"read_file","args":{"path":"package.json"}}
EOS

Example 2 (no tool needed):
reasoning: The answer is 42.
EOS

IMPORTANT: Always start with "reasoning:" and use "tool_call:" if you need to use a tool.`;

  // Step 2: Define tools (from AgentBridge.getAvailableTools)
  const tools = [
    {
      type: 'function',
      function: {
        name: 'read_file',
        description: 'Read the contents of a file',
        parameters: {
          type: 'object',
          properties: {
            path: { type: 'string', description: 'File path relative to project root' }
          },
          required: ['path']
        }
      }
    },
    {
      type: 'function',
      function: {
        name: 'list_files',
        description: 'List files and directories',
        parameters: {
          type: 'object',
          properties: {
            path: { type: 'string', description: 'Directory path' },
            recursive: { type: 'boolean', description: 'Whether to list recursively' }
          }
        }
      }
    }
  ];

  const messages = [
    { role: 'system', content: systemPrompt },
    { role: 'user', content: 'List files in the project' }
  ];

  console.log('Model:', MODEL_ID);
  console.log('Messages:', messages.length);
  console.log('Tools:', tools.length);
  console.log('');

  // Step 3: Call LLM (exactly like cliIntegration.callLLM)
  console.log('Calling Ollama API...');
  const startTime = Date.now();
  
  try {
    const body = {
      model: MODEL_ID,
      messages: messages,
      stream: false,
      options: {
        temperature: 0.2,
        top_p: 0.95,
        num_predict: 4096
      },
      tools: tools
    };

    const res = await fetch('http://localhost:11434/api/chat', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify(body)
    });

    const elapsed = Date.now() - startTime;
    console.log(`HTTP Status: ${res.status} ${res.statusText} (${elapsed}ms)`);

    if (!res.ok) {
      const errorText = await res.text();
      console.error('Error response:', errorText.substring(0, 500));
      process.exit(1);
    }

    const data = await res.json();
    
    console.log('\n=== Response ===');
    console.log('Content length:', data.message?.content?.length || 0);
    console.log('Tool calls:', data.message?.tool_calls?.length || 0);
    
    if (data.message?.content) {
      console.log('\nContent preview:');
      console.log(data.message.content.substring(0, 500));
    }
    
    if (data.message?.tool_calls?.length > 0) {
      console.log('\nTool calls:');
      data.message.tool_calls.forEach((tc, i) => {
        console.log(`  [${i}] ${tc.function?.name}(${JSON.stringify(tc.function?.arguments)})`);
      });
    }
    
    console.log('\n✅ Test passed!');
  } catch (error) {
    console.error('❌ Test failed:', error.message);
    console.error('Stack:', error.stack);
    process.exit(1);
  }
}

testAgentFlow();
