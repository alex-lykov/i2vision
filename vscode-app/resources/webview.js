/*
 * WebView JavaScript for i2-Vision Agent Tab
 * Model list comes 100% from Ollama API - NO hardcoded models
 */

(function() {
    'use strict';

    document.addEventListener('DOMContentLoaded', function() {
        initializeWebView();
    });

    function initializeWebView() {
        const vscode = acquireVsCodeApi();
        const messagesDiv = document.getElementById('messages');
        const actionButton = document.getElementById('actionButton');
        const userInput = document.getElementById('userInput');
        const providerSelect = document.getElementById('providerSelect');
        const modelSelect = document.getElementById('modelSelect');

        let isProcessing = false;
        let streamingMessageDiv = null;
        let ollamaModelsCache = [];

        // Load config from HTML
        const htmlEl = document.documentElement;
        const providerId = htmlEl.dataset.providerId || 'ollama';
        const modelId = htmlEl.dataset.modelId || '';
        
        console.log('[WebView] === INITIALIZING ===');
        console.log('[WebView] Provider:', providerId);
        console.log('[WebView] Model:', modelId);

        // =====================================================================
        // MESSAGE HANDLER - REGISTER FIRST
        // =====================================================================
        
        window.addEventListener('message', function(event) {
            const message = event.data;
            console.log('[WebView] ← Received message:', message.command);
            
            if (message.command === 'ollamaModels') {
                console.log('[WebView] ← Ollama models response:', message.models?.length || 0, 'models');
                if (message.models && message.models.length > 0) {
                    ollamaModelsCache = message.models;
                    console.log('[WebView] Cached models:', ollamaModelsCache.map(m => m.name));
                    populateModelDropdown(ollamaModelsCache, modelId);
                } else {
                    console.warn('[WebView] ⚠️ No models received from Ollama');
                    console.warn('[WebView] Error:', message.error || 'unknown');
                    populateModelDropdown([], modelId);
                }
            }
            
            handleMessage(message);
        });

        // =====================================================================
        // EVENT HANDLERS
        // =====================================================================

        window.onProviderChange = function() {
            console.log('[WebView] Provider changed to:', providerSelect.value);
            vscode.postMessage({ command: 'changeProvider', provider: providerSelect.value });
        };

        window.onModelChange = function() {
            console.log('[WebView] Model changed to:', modelSelect.value);
            vscode.postMessage({ command: 'changeModel', model: modelSelect.value });
        };

        window.handleKeyPress = function(event) {
            if (event.key === 'Enter' && !event.shiftKey) {
                event.preventDefault();
                if (!isProcessing) sendMessage();
            }
        };

        window.toggleAction = function() {
            if (isProcessing) stopAgent();
            else sendMessage();
        };

        // =====================================================================
        // REQUEST MODELS FROM EXTENSION
        // =====================================================================

        console.log('[WebView] → Requesting Ollama models...');
        vscode.postMessage({ command: 'getOllamaModels' });

        // Initialize dropdown (will be populated when models arrive)
        populateModelDropdown([], modelId);

        // =====================================================================
        // CORE FUNCTIONS
        // =====================================================================

        function sendMessage() {
            const text = userInput.value.trim();
            if (!text) return;

            isProcessing = true;
            userInput.disabled = true;
            updateActionButton();
            addMessage('user', text);

            streamingMessageDiv = document.createElement('div');
            streamingMessageDiv.className = 'message agent-message';
            streamingMessageDiv.innerHTML = '<em class="text-muted">Thinking...</em>';
            messagesDiv.appendChild(streamingMessageDiv);
            messagesDiv.scrollTop = messagesDiv.scrollHeight;

            vscode.postMessage({ command: 'sendMessage', text });
            userInput.value = '';
        }

        function updateActionButton() {
            actionButton.textContent = isProcessing ? 'Stop' : 'Send';
            actionButton.className = isProcessing ? 'stop-button' : '';
        }

        function addMessage(type, content) {
            const div = document.createElement('div');
            div.className = 'message ' + (type === 'user' ? 'user-message' : 'agent-message');
            div.textContent = content;
            messagesDiv.appendChild(div);
            messagesDiv.scrollTop = messagesDiv.scrollHeight;
        }

        function stopAgent() {
            vscode.postMessage({ command: 'stopAgent' });
        }

        // =====================================================================
        // DROPDOWN POPULATION
        // =====================================================================

        function populateModelDropdown(models, selectedModelId) {
            console.log('[WebView] Populating dropdown with', models.length, 'models');
            modelSelect.innerHTML = '';
            
            if (!models || models.length === 0) {
                console.warn('[WebView] No models to display');
                const option = document.createElement('option');
                option.value = '';
                option.textContent = 'No models available (is Ollama running?)';
                option.disabled = true;
                modelSelect.appendChild(option);
                return;
            }
            
            models.forEach(function(model) {
                const option = document.createElement('option');
                const modelName = model.name || model.id || 'unknown';
                option.value = modelName;
                option.textContent = modelName.replace(':latest', '');
                
                if (modelName === selectedModelId) {
                    option.selected = true;
                    console.log('[WebView] Selected model:', modelName);
                }
                
                modelSelect.appendChild(option);
            });
            
            if (!selectedModelId && modelSelect.options.length > 0) {
                modelSelect.options[0].selected = true;
            }
            
            console.log('[WebView] Dropdown ready -', modelSelect.options.length, 'options');
        }

        // =====================================================================
        // RENDER RESPONSE CARD
        // =====================================================================

        function renderResponseCard(response) {
            console.log('[WebView] Rendering response card:', response);
            
            const card = document.createElement('div');
            card.className = 'agent-output-card';
            
            const provider = response.provider || 'ollama';
            const model = response.model || modelId;
            const status = response.error ? 'error' : 'success';
            const statusIcon = response.error ? '❌' : '✓';
            
            // Build tool cards HTML
            let toolCardsHtml = '';
            if (response.toolCards && response.toolCards.length > 0) {
                toolCardsHtml = '<div class="tool-section"><div class="section-header"><span class="section-icon">🛠️</span><span class="section-label">Tools Used</span></div><div class="tool-calls-list">';
                
                response.toolCards.forEach(function(tool) {
                    const toolStatus = tool.error ? 'error' : 'success';
                    const toolStatusIcon = tool.error ? '✗' : '✓';
                    const argsJson = tool.args ? JSON.stringify(tool.args, null, 2) : '{}';
                    const resultPreview = tool.result ? String(tool.result).substring(0, 200) + (tool.result.length > 200 ? '...' : '') : 'No result';
                    
                    toolCardsHtml += `
                        <div class="tool-call-card ${toolStatus}">
                            <div class="tool-call-header" onclick="this.nextElementSibling.style.display = this.nextElementSibling.style.display === 'none' ? 'block' : 'none'">
                                <span class="tool-call-toggle">▶</span>
                                <span class="tool-call-status">${toolStatusIcon}</span>
                                <span class="tool-call-name">${tool.toolName}</span>
                                <div class="tool-call-meta">
                                    <span class="tool-call-duration">${tool.durationMs || 0}ms</span>
                                </div>
                            </div>
                            <div class="tool-call-body">
                                <div class="tool-call-args">
                                    <span class="args-label">Arguments:</span>
                                    <pre>${escapeHtml(argsJson)}</pre>
                                </div>
                                <div class="tool-call-result">
                                    <span class="result-label">Result:</span>
                                    <pre>${escapeHtml(resultPreview)}</pre>
                                </div>
                                ${tool.error ? `<div class="tool-call-error"><span class="error-label">Error:</span>${escapeHtml(tool.error)}</div>` : ''}
                            </div>
                        </div>
                    `;
                });
                
                toolCardsHtml += '</div></div>';
            }
            
            // Format response text with markdown-like formatting
            const formattedText = formatResponseText(response.text || '');
            
            card.innerHTML = `
                <div class="output-card-header">
                    <div class="header-left">
                        <span class="provider-badge" style="background: ${provider === 'ollama' ? '#007acc' : '#4caf50'}">${provider}</span>
                        <span class="model-name">${model}</span>
                    </div>
                    <div class="header-right">
                        <span class="status-badge status-${status}">${statusIcon}</span>
                    </div>
                </div>
                <div class="output-card-content">
                    ${response.reasoning ? `
                        <div class="reasoning-section">
                            <div class="section-header" onclick="this.nextElementSibling.style.display = this.nextElementSibling.style.display === 'none' ? 'block' : 'none'">
                                <span class="section-icon">🤔</span>
                                <span class="section-label">Reasoning</span>
                                <span class="section-toggle">▼</span>
                            </div>
                            <div class="reasoning-content">
                                <div class="reasoning-text">${formatResponseText(response.reasoning)}</div>
                            </div>
                        </div>
                    ` : ''}
                    <div class="response-text-section">
                        <div class="response-text">${formattedText}</div>
                    </div>
                    ${toolCardsHtml}
                </div>
                <div class="output-card-footer">
                    <div class="footer-meta">
                        <span class="meta-item">⏱ ${response.durationMs || 0}ms</span>
                        <span class="meta-item">🔄 ${response.iterations || 1} iterations</span>
                        ${response.tokenCount ? `<span class="meta-item">📝 ${response.tokenCount} tokens</span>` : ''}
                    </div>
                    <div class="footer-actions">
                        <button class="footer-action-btn" onclick="copyResponse()">
                            <span class="action-icon">📋</span>
                            <span>Copy</span>
                        </button>
                        <button class="footer-action-btn" onclick="applyToFile()">
                            <span class="action-icon">📝</span>
                            <span>Apply</span>
                        </button>
                    </div>
                </div>
            `;
            
            messagesDiv.appendChild(card);
            messagesDiv.scrollTop = messagesDiv.scrollHeight;
            
            console.log('[WebView] ✅ Response card rendered');
        }

        function formatResponseText(text) {
            if (!text) return '';
            
            // Convert markdown code blocks
            text = text.replace(/```(\w*)\n([\s\S]*?)```/g, function(match, lang, code) {
                return `<div class="code-block"><code>${escapeHtml(code.trim())}</code></div>`;
            });
            
            // Convert inline code
            text = text.replace(/`([^`]+)`/g, '<span class="inline-code">$1</span>');
            
            // Convert bold
            text = text.replace(/\*\*([^*]+)\*\*/g, '<strong>$1</strong>');
            
            // Convert italic
            text = text.replace(/\*([^*]+)\*/g, '<em>$1</em>');
            
            // Convert line breaks to <br>
            text = text.replace(/\n/g, '<br>');
            
            return text;
        }

        function escapeHtml(text) {
            if (!text) return '';
            return text
                .replace(/&/g, '&amp;')
                .replace(/</g, '&lt;')
                .replace(/>/g, '&gt;')
                .replace(/"/g, '&quot;')
                .replace(/'/g, '&#039;');
        }

        window.copyResponse = function() {
            vscode.postMessage({ command: 'copyResponse' });
            showTemporaryFeedback('Response copied to clipboard');
        };

        window.applyToFile = function() {
            vscode.postMessage({ command: 'applyToFile' });
        };

        function showTemporaryFeedback(message) {
            const feedback = document.createElement('div');
            feedback.className = 'temporary-feedback';
            feedback.textContent = message;
            document.body.appendChild(feedback);
            
            setTimeout(function() {
                feedback.classList.add('fade-out');
                setTimeout(function() {
                    feedback.remove();
                }, 300);
            }, 2000);
        }

        // =====================================================================
        // PROGRESS EVENT HANDLER - REAL-TIME TOOL CARDS
        // =====================================================================

        function handleProgressEvent(event) {
            console.log('[WebView] Progress event:', event.type, event.toolCall?.toolName);
            
            if (event.type === 'tool_start' && event.toolCall) {
                // Create a new tool card when tool starts
                const toolCard = createToolCard(event.toolCall, false);
                
                // Ensure we have a progress container
                let progressContainer = document.getElementById('progress-container');
                if (!progressContainer) {
                    progressContainer = document.createElement('div');
                    progressContainer.id = 'progress-container';
                    progressContainer.className = 'tool-section';
                    progressContainer.innerHTML = '<div class="section-header"><span class="section-icon">🛠️</span><span class="section-label">Tools in Progress</span></div><div class="tool-calls-list"></div>';
                    
                    // Insert before streaming message or at end of messages
                    if (streamingMessageDiv) {
                        messagesDiv.insertBefore(progressContainer, streamingMessageDiv);
                    } else {
                        messagesDiv.appendChild(progressContainer);
                    }
                }
                
                const toolList = progressContainer.querySelector('.tool-calls-list');
                if (toolList) {
                    toolList.appendChild(toolCard);
                }
                messagesDiv.scrollTop = messagesDiv.scrollHeight;
                
            } else if (event.type === 'tool_complete' && event.toolCall) {
                // Update the tool card with result
                const toolCards = document.querySelectorAll('.tool-call-card[data-tool-name="' + event.toolCall.toolName + '"]');
                
                if (toolCards.length > 0) {
                    // Update the last matching card
                    const card = toolCards[toolCards.length - 1];
                    updateToolCard(card, event.toolCall, true);
                } else {
                    // Card not found, create new one (fallback)
                    const toolCard = createToolCard(event.toolCall, true);
                    let progressContainer = document.getElementById('progress-container');
                    if (!progressContainer) {
                        progressContainer = document.createElement('div');
                        progressContainer.id = 'progress-container';
                        progressContainer.className = 'tool-section';
                        progressContainer.innerHTML = '<div class="section-header"><span class="section-icon">🛠️</span><span class="section-label">Tools Used</span></div><div class="tool-calls-list"></div>';
                        messagesDiv.appendChild(progressContainer);
                    }
                    const toolList = progressContainer.querySelector('.tool-calls-list');
                    if (toolList) {
                        toolList.appendChild(toolCard);
                    }
                }
                messagesDiv.scrollTop = messagesDiv.scrollHeight;
            }
        }

        function createToolCard(toolCall, isComplete) {
            const card = document.createElement('div');
            card.className = 'tool-call-card ' + (isComplete && !toolCall.error ? 'success' : 'processing');
            card.setAttribute('data-tool-name', toolCall.toolName);
            
            const statusIcon = isComplete ? (toolCall.error ? '✗' : '✓') : '⏳';
            const statusClass = isComplete ? (toolCall.error ? 'status-error' : 'status-success') : 'status-pending';
            const argsJson = toolCall.args ? JSON.stringify(toolCall.args, null, 2) : '{}';
            
            card.innerHTML = `
                <div class="tool-call-header" onclick="this.nextElementSibling.style.display = this.nextElementSibling.style.display === 'none' ? 'block' : 'none'">
                    <span class="tool-call-toggle">▶</span>
                    <span class="tool-call-status" style="background: ${toolCall.error ? 'var(--vscode-errorForeground)' : 'var(--vscode-terminal-ansiGreen)'}">${statusIcon}</span>
                    <span class="tool-call-name">${toolCall.toolName}</span>
                    <div class="tool-call-meta">
                        <span class="tool-call-duration">${isComplete ? 'completed' : 'running...'}</span>
                    </div>
                </div>
                <div class="tool-call-body">
                    <div class="tool-call-args">
                        <span class="args-label">Arguments:</span>
                        <pre>${escapeHtml(argsJson)}</pre>
                    </div>
                    ${isComplete ? `
                        <div class="tool-call-result">
                            <span class="result-label">Result:</span>
                            <pre>${escapeHtml(String(toolCall.result || 'No result').substring(0, 200))}</pre>
                        </div>
                        ${toolCall.error ? `<div class="tool-call-error"><span class="error-label">Error:</span>${escapeHtml(toolCall.error)}</div>` : ''}
                    ` : `
                        <div class="tool-call-result">
                            <span class="result-label">Result:</span>
                            <em class="text-muted">Waiting for result...</em>
                        </div>
                    `}
                </div>
            `;
            
            return card;
        }

        function updateToolCard(card, toolCall, isComplete) {
            const statusIcon = isComplete ? (toolCall.error ? '✗' : '✓') : '⏳';
            const resultPreview = toolCall.result ? String(toolCall.result).substring(0, 200) + (String(toolCall.result).length > 200 ? '...' : '') : 'No result';
            
            card.className = 'tool-call-card ' + (isComplete && !toolCall.error ? 'success' : 'processing');
            
            const statusSpan = card.querySelector('.tool-call-status');
            if (statusSpan) {
                statusSpan.textContent = statusIcon;
                statusSpan.style.background = toolCall.error ? 'var(--vscode-errorForeground)' : 'var(--vscode-terminal-ansiGreen)';
            }
            
            const durationSpan = card.querySelector('.tool-call-duration');
            if (durationSpan) {
                durationSpan.textContent = isComplete ? 'completed' : 'running...';
            }
            
            const resultDiv = card.querySelector('.tool-call-result');
            if (resultDiv && isComplete) {
                resultDiv.innerHTML = `
                    <span class="result-label">Result:</span>
                    <pre>${escapeHtml(resultPreview)}</pre>
                    ${toolCall.error ? `<div class="tool-call-error"><span class="error-label">Error:</span>${escapeHtml(toolCall.error)}</div>` : ''}
                `;
            }
        }

        // =====================================================================
        // MESSAGE HANDLER (other commands)
        // =====================================================================

        function handleMessage(message) {
            switch (message.command) {
                case 'processing':
                    console.log('[WebView] Processing:', message.userInput?.substring(0, 50));
                    break;

                case 'progress':
                    if (message.event) {
                        handleProgressEvent(message.event);
                    }
                    break;

                case 'streamingText':
                    if (streamingMessageDiv && message.accumulated) {
                        streamingMessageDiv.innerHTML = message.accumulated.replace(/\n/g, '<br>');
                        messagesDiv.scrollTop = messagesDiv.scrollHeight;
                    }
                    break;

                case 'response':
                    // Remove streaming placeholder
                    if (streamingMessageDiv) {
                        streamingMessageDiv.remove();
                        streamingMessageDiv = null;
                    }
                    
                    // Reset processing state
                    isProcessing = false;
                    userInput.disabled = false;
                    updateActionButton();
                    
                    // DON'T remove progress container — keep the real-time tool cards
                    // Just update the header from "Tools in Progress" to "Tools Used"
                    const progressContainer = document.getElementById('progress-container');
                    if (progressContainer) {
                        const header = progressContainer.querySelector('.section-label');
                        if (header) {
                            header.textContent = 'Tools Used';
                        }
                    }
                    
                    console.log('[WebView] Response received:', message.response?.text?.length, 'chars, toolCards:', message.response?.toolCards?.length || 0);
                    
                    // Add final response text after the tools (don't rebuild tool cards)
                    if (message.response && message.response.text) {
                        const responseDiv = document.createElement('div');
                        responseDiv.className = 'agent-output-card';
                        responseDiv.innerHTML = `
                            <div class="output-card-header">
                                <div class="header-left">
                                    <span class="provider-badge" style="background: ${message.response.provider || 'ollama' === 'ollama' ? '#007acc' : '#4caf50'}">${message.response.provider || 'ollama'}</span>
                                    <span class="model-name">${message.response.model || modelId}</span>
                                </div>
                                <div class="header-right">
                                    <span class="status-badge status-${message.response.error ? 'error' : 'success'}">${message.response.error ? '❌' : '✓'}</span>
                                </div>
                            </div>
                            <div class="output-card-content">
                                <div class="response-text-section">
                                    <div class="response-text">${formatResponseText(message.response.text)}</div>
                                </div>
                            </div>
                            <div class="output-card-footer">
                                <div class="footer-meta">
                                    <span class="meta-item">⏱ ${message.response.durationMs || 0}ms</span>
                                    <span class="meta-item">🔄 ${message.response.iterations || 1} iterations</span>
                                </div>
                                <div class="footer-actions">
                                    <button class="footer-action-btn" onclick="copyResponse()">
                                        <span class="action-icon">📋</span>
                                        <span>Copy</span>
                                    </button>
                                    <button class="footer-action-btn" onclick="applyToFile()">
                                        <span class="action-icon">📝</span>
                                        <span>Apply</span>
                                    </button>
                                </div>
                            </div>
                        `;
                        messagesDiv.appendChild(responseDiv);
                    }
                    
                    messagesDiv.scrollTop = messagesDiv.scrollHeight;
                    break;

                case 'stopped':
                    if (streamingMessageDiv) streamingMessageDiv.remove();
                    addMessage('agent', '⏹ Stopped by user');
                    isProcessing = false;
                    userInput.disabled = false;
                    updateActionButton();
                    break;

                case 'error':
                    if (streamingMessageDiv) streamingMessageDiv.remove();
                    addMessage('agent', 'Error: ' + message.error);
                    isProcessing = false;
                    userInput.disabled = false;
                    updateActionButton();
                    break;

                case 'configUpdated':
                    console.log('[WebView] Config updated:', message.provider, message.model);
                    if (message.provider) providerSelect.value = message.provider;
                    if (message.model) modelSelect.value = message.model;
                    break;
            }
        }
    }
})();
