// Debug test to see full response
const { exec } = require('child_process');
const { promisify } = require('util');
const execAsync = promisify(exec);

async function testLLM() {
  console.log('Testing LLM integration with full debug...');
  
  try {
    // Get available models
    const res = await fetch('http://localhost:11434/api/tags');
    const data = await res.json();
    console.log('Available models:', data.models?.map(m => m.name).join(', '));
    
    // Use first available model
    const model = data.models?.[0]?.name || 'gemma3:1b';
    console.log('Using model:', model);
    
    // Test chat endpoint with full response logging
    console.log('\nSending test message...');
    const chatRes = await fetch('http://localhost:11434/api/chat', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({
        model: model,
        messages: [{ role: 'user', content: 'Say hello' }],
        stream: false
      })
    });
    
    console.log('Status:', chatRes.status);
    const chatData = await chatRes.json();
    console.log('Full response:', JSON.stringify(chatData, null, 2));
    
  } catch (error) {
    console.error('❌ Test failed:', error.message);
    console.error('Stack:', error.stack);
    process.exit(1);
  }
}

testLLM();
