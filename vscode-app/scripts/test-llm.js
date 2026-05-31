// Test LLM integration with tool calls
async function testLLM() {
  console.log('Testing LLM integration with tools...\n');
  
  try {
    // Get available models
    const res = await fetch('http://localhost:11434/api/tags');
    const data = await res.json();
    console.log('Available models:', data.models?.map(m => m.name).join(', '));
    
    // Use a local model for testing (faster)
    const model = 'gemma3:1b';
    console.log(`\nUsing model: ${model}`);
    
    // Define a simple tool
    const tools = [{
      type: 'function',
      function: {
        name: 'read_file',
        description: 'Read a file from the filesystem',
        parameters: {
          type: 'object',
          properties: {
            path: { type: 'string', description: 'File path' }
          },
          required: ['path']
        }
      }
    }];
    
    // Test with tool
    console.log('\nSending message with tool...');
    const chatRes = await fetch('http://localhost:11434/api/chat', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({
        model: model,
        messages: [
          { role: 'system', content: 'You are a helpful assistant. Use tools when needed.' },
          { role: 'user', content: 'Read the file package.json' }
        ],
        tools: tools,
        stream: false
      })
    });
    
    console.log('Status:', chatRes.status);
    const chatData = await chatRes.json();
    
    console.log('\nResponse:');
    console.log('  Content:', chatData.message?.content?.substring(0, 200) || '(empty)');
    console.log('  Tool calls:', chatData.message?.tool_calls?.length || 0);
    
    if (chatData.message?.tool_calls?.length > 0) {
      console.log('\nTool call details:');
      chatData.message.tool_calls.forEach((tc, i) => {
        console.log(`  [${i}] ${tc.function?.name}(${JSON.stringify(tc.function?.arguments)})`);
      });
    }
    
    console.log('\n✅ LLM test passed!');
  } catch (error) {
    console.error('❌ LLM test failed:', error.message);
    console.error('Stack:', error.stack);
    process.exit(1);
  }
}

testLLM();
