/*
 * WebView JavaScript for i2-Vision Agent Tab
 * Model list comes 100% from Ollama API - NO hardcoded models
 */

(function() {
    'use strict';

    document.addEventListener('DOMContentLoaded', function() {
        initializeWebView();
    });

    /**
     * Inject enhanced tool card styles
     */
    function injectToolCardStyles() {
        const style = document.createElement('style');
        style.textContent = `
            /* Layer selector */
            .layer-selector-container {
                display: flex;
                align-items: center;
                gap: 8px;
                padding: 8px 12px;
                background-color: var(--vscode-panelSectionHeader-background);
                border-bottom: 1px solid var(--vscode-panelSectionHeader-border);
                margin-bottom: 10px;
            }
            
            .layer-selector-label {
                font-size: 12px;
                font-weight: 600;
                color: var(--vscode-foreground);
            }
            
            .layer-selector {
                padding: 4px 8px;
                font-size: 12px;
                background-color: var(--vscode-dropdown-background);
                color: var(--vscode-dropdown-foreground);
                border: 1px solid var(--vscode-dropdown-border);
                border-radius: 2px;
                cursor: pointer;
            }
            
            .layer-selector:hover {
                border-color: var(--vscode-focusBorder);
            }
            
            .layer-badge {
                display: inline-flex;
                align-items: center;
                gap: 4px;
                padding: 2px 8px;
                border-radius: 12px;
                font-size: 11px;
                font-weight: 600;
                text-transform: uppercase;
                margin-left: 8px;
            }
            
            .layer-badge-vision { background-color: #e91e6320; color: #e91e63; border: 1px solid #e91e63; }
            .layer-badge-structure { background-color: #9c27b020; color: #9c27b0; border: 1px solid #9c27b0; }
            .layer-badge-logic { background-color: #2196f320; color: #2196f3; border: 1px solid #2196f3; }
            .layer-badge-flow { background-color: #ff980020; color: #ff9800; border: 1px solid #ff9800; }
            .layer-badge-code { background-color: #4caf5020; color: #4caf50; border: 1px solid #4caf50; }
            
            /* Tool category badge */
            .tool-category-badge {
                display: inline-flex;
                align-items: center;
                justify-content: center;
                padding: 2px 6px;
                border-radius: 3px;
                border: 1px solid;
                margin-right: 6px;
                font-size: 11px;
            }
            
            .tool-category-badge .codicon {
                font-size: 12px;
                line-height: 1;
            }
            
            /* Help icon (question mark) */
            .tool-help-icon {
                display: inline-flex;
                align-items: center;
                justify-content: center;
                width: 16px;
                height: 16px;
                border-radius: 50%;
                background-color: var(--vscode-descriptionForeground);
                color: var(--vscode-foreground);
                font-size: 10px;
                margin-left: 4px;
                cursor: help;
                opacity: 0.7;
                transition: opacity 0.2s;
            }
            
            .tool-help-icon:hover {
                opacity: 1;
            }
            
            .tool-help-icon .codicon {
                font-size: 10px;
            }
            
            /* Enhanced tool call card header */
            .tool-call-header {
                display: flex;
                align-items: center;
                gap: 6px;
                padding: 8px 12px;
                background-color: var(--vscode-panelSectionHeader-background);
                border-bottom: 1px solid var(--vscode-panelSectionHeader-border);
                cursor: pointer;
                user-select: none;
            }
            
            .tool-call-header:hover {
                background-color: var(--vscode-panelSectionHeader-hoverBackground);
            }
            
            .tool-call-icon {
                font-size: 14px;
            }
            
            .tool-call-name {
                font-weight: 600;
                font-size: 13px;
                color: var(--vscode-foreground);
            }
            
            .tool-call-meta {
                margin-left: auto;
                display: flex;
                align-items: center;
                gap: 8px;
                font-size: 11px;
                color: var(--vscode-descriptionForeground);
            }
            
            .tool-call-duration {
                background-color: var(--vscode-badge-background);
                color: var(--vscode-badge-foreground);
                padding: 2px 6px;
                border-radius: 2px;
            }
            
            .tool-call-id {
                font-family: var(--vscode-editor-font-family);
                font-size: 10px;
                opacity: 0.7;
            }
            
            /* Tool call body */
            .tool-call-body {
                padding: 12px;
                background-color: var(--vscode-panel-background);
                display: block;
            }
            
            .tool-call-args,
            .tool-call-result {
                margin-bottom: 10px;
            }
            
            .tool-call-args:last-child,
            .tool-call-result:last-child {
                margin-bottom: 0;
            }
            
            .args-label,
            .result-label,
            .error-label {
                display: block;
                font-weight: 600;
                font-size: 11px;
                text-transform: uppercase;
                color: var(--vscode-descriptionForeground);
                margin-bottom: 4px;
            }
            
            .tool-call-args pre,
            .tool-call-result pre {
                margin: 0;
                padding: 10px;
                background-color: var(--vscode-textCodeBlock-background);
                border-radius: 4px;
                font-family: var(--vscode-editor-font-family);
                font-size: 12px;
                overflow-x: auto;
                max-height: 300px;
                overflow-y: auto;
            }
            
            .tool-call-error {
                padding: 10px;
                background-color: var(--vscode-inputValidation-errorBackground);
                border: 1px solid var(--vscode-inputValidation-errorBorder);
                border-radius: 4px;
                color: var(--vscode-errorForeground);
            }
            
            /* Success/error states */
            .tool-call-card.success .tool-call-header {
                border-left: 3px solid var(--vscode-terminal-ansiGreen);
            }
            
            .tool-call-card.processing .tool-call-header {
                border-left: 3px solid var(--vscode-progressBar-background);
            }
            
            .tool-call-card.error .tool-call-header {
                border-left: 3px solid var(--vscode-errorForeground);
            }
            
            /* Compact mode */
            .tool-call-card.compact .tool-call-body {
                display: none;
            }
            
            /* Collapsed state */
            .tool-call-card.collapsed .tool-call-body {
                display: none;
            }
            
            /* Context Meter */
            .context-meter {
                position: sticky;
                top: 0;
                z-index: 100;
                padding: 10px 12px;
                background-color: var(--vscode-sideBar-background);
                border-bottom: 1px solid var(--vscode-widget-border);
                margin-bottom: 10px;
            }
            
            .meter-header {
                display: flex;
                justify-content: space-between;
                align-items: center;
                margin-bottom: 6px;
            }
            
            .meter-label {
                font-size: 11px;
                font-weight: 600;
                text-transform: uppercase;
                color: var(--vscode-descriptionForeground);
            }
            
            .meter-values {
                display: flex;
                gap: 8px;
                font-size: 11px;
                color: var(--vscode-foreground);
            }
            
            .token-count {
                font-family: var(--vscode-editor-font-family);
            }
            
            .percentage {
                font-weight: 600;
            }
            
            .meter-bar {
                height: 6px;
                background-color: var(--vscode-input-background);
                border-radius: 3px;
                overflow: hidden;
            }
            
            .meter-fill {
                height: 100%;
                background-color: var(--vscode-terminal-ansiGreen);
                transition: width 0.3s ease, background-color 0.3s ease;
                border-radius: 3px;
            }
        `;
        document.head.appendChild(style);
    }

    function initializeWebView() {
        const vscode = acquireVsCodeApi();
        const messagesDiv = document.getElementById('messages');
        const actionButton = document.getElementById('actionButton');
        const userInput = document.getElementById('userInput');
        const providerSelect = document.getElementById('providerSelect');
        const modelSelect = document.getElementById('modelSelect');

        // Inject enhanced tool card styles
        injectToolCardStyles();

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
        
        window.onLayerChange = function() {
            const layerSelect = document.getElementById('layerSelect');
            console.log('[WebView] Layer changed to:', layerSelect.value);
            vscode.postMessage({ command: 'changeLayer', layer: layerSelect.value });
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
                    
                    // Get category info
                    const categoryInfo = getToolCategory(tool.toolName);
                    const description = TOOL_DESCRIPTIONS[tool.toolName];
                    
                    // Build category badge HTML
                    const categoryBadge = categoryInfo ? `
                        <span class="tool-category-badge" style="background-color: ${categoryInfo.color}20; border-color: ${categoryInfo.color};" 
                              title="${categoryInfo.displayName}">
                            <span class="codicon codicon-${categoryInfo.icon}" style="color: ${categoryInfo.color};"></span>
                        </span>
                    ` : '';
                    
                    // Build help tooltip HTML
                    const helpTooltip = description ? `
                        <span class="tool-help-icon" title="${escapeHtml(description)}">
                            <span class="codicon codicon-question"></span>
                        </span>
                    ` : '';
                    
                    toolCardsHtml += `
                        <div class="tool-call-card ${toolStatus}" data-tool-call-id="${tool.toolCallId || ''}">
                            <div class="tool-call-header" onclick="toggleToolCallCard(this)">
                                <span class="tool-call-status">${toolStatusIcon}</span>
                                ${categoryBadge}
                                <span class="tool-call-name">${tool.toolName}</span>
                                ${helpTooltip}
                                <div class="tool-call-meta">
                                    <span class="tool-call-duration">${tool.durationMs || 0}ms</span>
                                    ${tool.toolCallId ? `<span class="tool-call-id" title="Tool Call ID">${escapeHtml(tool.toolCallId)}</span>` : ''}
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
        // CONTEXT METER - TOKEN USAGE DISPLAY
        // =====================================================================
        
        let contextMeterDiv = null;
        
        function updateContextMeter(tokenUsage, contextLength) {
            if (!tokenUsage || !contextLength) return;
            
            const totalTokens = tokenUsage.prompt + tokenUsage.completion;
            // Cap percentage at 100% for display purposes
            const rawPercentage = ((totalTokens / contextLength) * 100);
            const percentage = Math.min(rawPercentage, 100).toFixed(1);
            
            // Create meter UI if it doesn't exist
            if (!contextMeterDiv) {
                contextMeterDiv = document.createElement('div');
                contextMeterDiv.id = 'context-meter';
                contextMeterDiv.className = 'context-meter';
                contextMeterDiv.innerHTML = `
                    <div class="meter-header">
                        <span class="meter-label">Context Usage</span>
                        <span class="meter-values">
                            <span class="token-count">${totalTokens.toLocaleString()} / ${contextLength.toLocaleString()}</span>
                            <span class="percentage">${percentage}%</span>
                        </span>
                    </div>
                    <div class="meter-bar">
                        <div class="meter-fill" style="width: ${percentage}%"></div>
                    </div>
                `;
                
                // Insert at the top of the messages area
                if (messagesDiv.firstChild) {
                    messagesDiv.insertBefore(contextMeterDiv, messagesDiv.firstChild);
                } else {
                    messagesDiv.appendChild(contextMeterDiv);
                }
            } else {
                // Update existing meter
                const tokenCountEl = contextMeterDiv.querySelector('.token-count');
                const percentageEl = contextMeterDiv.querySelector('.percentage');
                const fillEl = contextMeterDiv.querySelector('.meter-fill');
                
                if (tokenCountEl) tokenCountEl.textContent = `${totalTokens.toLocaleString()} / ${contextLength.toLocaleString()}`;
                if (percentageEl) percentageEl.textContent = `${percentage}%`;
                if (fillEl) fillEl.style.width = `${percentage}%`;
            }
            
            // Update color based on usage
            const fillEl = contextMeterDiv?.querySelector('.meter-fill');
            if (fillEl) {
                const pct = parseFloat(percentage);
                if (pct >= 80) {
                    fillEl.style.background = 'var(--vscode-terminal-ansiRed)';
                } else if (pct >= 50) {
                    fillEl.style.background = 'var(--vscode-terminal-ansiYellow)';
                } else {
                    fillEl.style.background = 'var(--vscode-terminal-ansiGreen)';
                }
            }
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
            
            // Get category info
            const categoryInfo = getToolCategory(toolCall.toolName);
            const description = TOOL_DESCRIPTIONS[toolCall.toolName];

            // Build category badge HTML
            const categoryBadge = categoryInfo ? `
                <span class="tool-category-badge" style="background-color: ${categoryInfo.color}20; border-color: ${categoryInfo.color};" 
                      title="${categoryInfo.displayName}">
                    <span class="codicon codicon-${categoryInfo.icon}" style="color: ${categoryInfo.color};"></span>
                </span>
            ` : '';

            // Build help tooltip HTML
            const helpTooltip = description ? `
                <span class="tool-help-icon" title="${escapeHtml(description)}">
                    <span class="codicon codicon-question"></span>
                </span>
            ` : '';

            card.innerHTML = `
                <div class="tool-call-header" onclick="toggleToolCallCard(this)">
                    <span class="tool-call-status" style="background: ${toolCall.error ? 'var(--vscode-errorForeground)' : 'var(--vscode-terminal-ansiGreen)'}">${statusIcon}</span>
                    ${categoryBadge}
                    <span class="tool-call-name">${toolCall.toolName}</span>
                    ${helpTooltip}
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

        /**
         * Tool category icon mappings
         */
        function getToolCategory(toolName) {
            const toolCategoryMap = {
                'list_directory': 'file',
                'read_file': 'file',
                'write_file': 'file',
                'search_files': 'file',
                'get_file_context': 'file',
                'revert_file': 'file',
                'revert_all': 'file',
                'list_snapshots': 'file',
                'git_status': 'git',
                'git_diff': 'git',
                'git_log': 'git',
                'git_branch': 'git',
                'git_commit': 'git',
                'run_build': 'build',
                'run_terminal': 'terminal',
                'kill_terminal': 'terminal',
                'list_terminals': 'terminal',
                'terminal_status': 'terminal',
                'kill_port': 'terminal',
                'apply_edits': 'edit'
            };

            const category = toolCategoryMap[toolName];
            const categoryIcons = {
                file: { icon: 'file', color: '#007acc', displayName: 'File Operations' },
                git: { icon: 'git-commit', color: '#6f42c1', displayName: 'Git Operations' },
                build: { icon: 'gear', color: '#d46b08', displayName: 'Build & Compile' },
                terminal: { icon: 'terminal', color: '#2ea043', displayName: 'Terminal' },
                edit: { icon: 'edit', color: '#d29922', displayName: 'Edit Operations' }
            };

            return category ? categoryIcons[category] : undefined;
        }

        /**
         * Tool descriptions for tooltips
         */
        const TOOL_DESCRIPTIONS = {
            'list_directory': 'List files in a directory',
            'read_file': 'Read contents of a file',
            'write_file': 'Write content to a file',
            'apply_edits': 'Apply targeted edits to an existing file (max 50 edits)',
            'search_files': 'Search for files matching a regex pattern',
            'get_file_context': 'Get context for a specific file (classes, functions, imports)',
            'revert_file': 'Revert a file to its original state',
            'revert_all': 'Revert ALL modified files to their original state',
            'list_snapshots': 'List all files that have been modified',
            'git_status': 'Show working tree status',
            'git_diff': 'Show changes between commits',
            'git_log': 'Show recent commit history',
            'git_branch': 'Show current or all branches',
            'git_commit': 'Stage files and create a commit',
            'run_build': 'Run a build command',
            'run_terminal': 'Run a terminal command',
            'kill_terminal': 'Stop a running terminal by name',
            'list_terminals': 'List all managed terminals',
            'terminal_status': 'Check if a terminal is running',
            'kill_port': 'Find and kill the process using a specific port'
        };

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
                durationSpan.textContent = isComplete ? `${toolCall.durationMs || 0}ms` : 'running...';
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
        
        /**
         * Toggle tool call card expand/collapse (global function for onclick)
         */
        window.toggleToolCallCard = function(header) {
            const body = header.nextElementSibling;
            const card = header.parentElement;
            
            if (body.style.display === 'none') {
                body.style.display = 'block';
                card.classList.remove('collapsed');
            } else {
                body.style.display = 'none';
                card.classList.add('collapsed');
            }
        };

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

                case 'token_usage':
                    console.log('[WebView] Token usage:', message.tokenUsage);
                    updateContextMeter(message.tokenUsage, message.contextLength);
                    break;

                case 'context_meter_update':
                    console.log('[WebView] Context meter update:', message.summary);
                    updateContextMeterDetailed(message.summary);
                    break;

                case 'proxy_dashboard':
                    console.log('[WebView] Proxy dashboard:', message);
                    updateProxyDashboard(message);
                    break;
            }
        }
    }

    /**
     * Update the proxy health dashboard in the webview.
     */
    function updateProxyDashboard(data) {
        let dashDiv = document.getElementById('proxy-dashboard');
        if (!dashDiv) {
            dashDiv = document.createElement('div');
            dashDiv.id = 'proxy-dashboard';
            dashDiv.className = 'proxy-dashboard';
            // Insert after context meter if exists, else at top of messages
            const contextMeter = document.getElementById('context-meter-detailed');
            if (contextMeter && contextMeter.nextSibling) {
                messagesDiv.insertBefore(dashDiv, contextMeter.nextSibling);
            } else {
                messagesDiv.insertBefore(dashDiv, messagesDiv.firstChild);
            }
        }

        if (data.error) {
            dashDiv.innerHTML = `<div class="dash-error" style="color:var(--vscode-terminal-ansiRed);font-size:12px;padding:4px 8px;">⚠️ ${data.error}</div>`;
            return;
        }

        const health = data.health || {};
        const session = data.session || {};
        const ctx = data.contextStatus || {};
        const warnings = data.warnings || [];

        const statusColor = health.healthy ? 'var(--vscode-terminal-ansiGreen)' : 'var(--vscode-terminal-ansiRed)';
        const statusText = health.healthy ? 'Online' : 'Offline';

        let html = `
            <div class="dash-header" style="display:flex;justify-content:space-between;align-items:center;padding:4px 8px;border-bottom:1px solid var(--vscode-panel-border);font-size:12px;">
                <span style="font-weight:600;">3D LLM Proxy</span>
                <span style="color:${statusColor};">${statusText} · ${health.agents || 0} agents</span>
            </div>
        `;

        if (session.id) {
            html += `
                <div class="dash-row" style="display:flex;justify-content:space-between;padding:2px 8px;font-size:11px;">
                    <span>Session</span>
                    <span>${session.id.substring(0, 8)}… · ${session.messageCount} msgs</span>
                </div>
            `;
        }

        if (ctx.tokens) {
            html += `
                <div class="dash-row" style="display:flex;justify-content:space-between;padding:2px 8px;font-size:11px;">
                    <span>Tokens</span>
                    <span>${ctx.tokens.used.toLocaleString()} / ${ctx.tokens.total.toLocaleString()}</span>
                </div>
            `;
        }

        if (warnings.length > 0) {
            html += `
                <div class="dash-warnings" style="margin-top:4px;padding:4px 8px;background:var(--vscode-inputValidation-warningBackground);border-radius:3px;font-size:11px;">
                    ${warnings.map(w => `<div>⚠️ ${w}</div>`).join('')}
                </div>
            `;
        }

        dashDiv.innerHTML = html;
    }

    /**
     * Update the detailed context meter with full ContextMeter summary data.
     */
    function updateContextMeterDetailed(summary) {
        if (!summary) return;

        let meterDiv = document.getElementById('context-meter-detailed');
        if (!meterDiv) {
            meterDiv = document.createElement('div');
            meterDiv.id = 'context-meter-detailed';
            meterDiv.className = 'context-meter-detailed';
            messagesDiv.insertBefore(meterDiv, messagesDiv.firstChild);
        }

        const t = summary.tokens;
        const m = summary.messages;
        const ttl = summary.sessionTtl;
        const lat = summary.latency;

        const statusColors = {
            healthy: 'var(--vscode-terminal-ansiGreen)',
            warning: 'var(--vscode-terminal-ansiYellow)',
            critical: 'var(--vscode-terminal-ansiRed)',
            exhausted: 'var(--vscode-terminal-ansiBrightRed)',
        };
        const statusColor = statusColors[summary.status] || statusColors.healthy;

        let html = `
            <div class="meter-summary" style="border-left: 3px solid ${statusColor}; padding-left: 8px; margin-bottom: 8px;">
                <div class="meter-status" style="font-weight: 600; color: ${statusColor};">${summary.statusText}</div>
                <div class="meter-timestamp" style="font-size: 11px; opacity: 0.7;">${new Date(summary.timestamp).toLocaleTimeString()}</div>
            </div>
            <div class="meter-section" style="margin-bottom: 6px;">
                <div class="meter-row" style="display: flex; justify-content: space-between; font-size: 12px;">
                    <span>Tokens</span>
                    <span>${t.used.toLocaleString()} / ${t.total.toLocaleString()} (${t.percentage.toFixed(1)}%)</span>
                </div>
                <div class="meter-bar-bg" style="background: var(--vscode-panel-border); height: 4px; border-radius: 2px; overflow: hidden; margin-top: 2px;">
                    <div class="meter-bar-fill" style="width: ${Math.min(t.percentage, 100)}%; height: 100%; background: ${t.percentage >= 80 ? 'var(--vscode-terminal-ansiRed)' : t.percentage >= 50 ? 'var(--vscode-terminal-ansiYellow)' : 'var(--vscode-terminal-ansiGreen)'};"></div>
                </div>
                <div class="meter-detail" style="font-size: 11px; opacity: 0.8; margin-top: 2px;">
                    prompt: ${t.prompt.toLocaleString()} | completion: ${t.completion.toLocaleString()}
                    ${t.reasoning !== undefined ? `| reasoning: ${t.reasoning.toLocaleString()}` : ''}
                    | remaining: ${t.remaining.toLocaleString()}
                </div>
            </div>
        `;

        if (m.total !== Infinity) {
            html += `
                <div class="meter-section" style="margin-bottom: 6px;">
                    <div class="meter-row" style="display: flex; justify-content: space-between; font-size: 12px;">
                        <span>Messages</span>
                        <span>${m.used.toLocaleString()} / ${m.total.toLocaleString()} (${m.percentage.toFixed(1)}%)</span>
                    </div>
                    <div class="meter-bar-bg" style="background: var(--vscode-panel-border); height: 4px; border-radius: 2px; overflow: hidden; margin-top: 2px;">
                        <div class="meter-bar-fill" style="width: ${Math.min(m.percentage, 100)}%; height: 100%; background: ${m.percentage >= 80 ? 'var(--vscode-terminal-ansiRed)' : m.percentage >= 50 ? 'var(--vscode-terminal-ansiYellow)' : 'var(--vscode-terminal-ansiGreen)'};"></div>
                    </div>
                </div>
            `;
        }

        if (ttl.totalMs !== Infinity) {
            const ttlRemainingMins = Math.ceil(ttl.remainingMs / 60000);
            html += `
                <div class="meter-section" style="margin-bottom: 6px;">
                    <div class="meter-row" style="display: flex; justify-content: space-between; font-size: 12px;">
                        <span>Session TTL</span>
                        <span>${ttlRemainingMins}m remaining</span>
                    </div>
                    <div class="meter-bar-bg" style="background: var(--vscode-panel-border); height: 4px; border-radius: 2px; overflow: hidden; margin-top: 2px;">
                        <div class="meter-bar-fill" style="width: ${Math.min(ttl.percentage, 100)}%; height: 100%; background: ${ttl.percentage >= 80 ? 'var(--vscode-terminal-ansiRed)' : ttl.percentage >= 50 ? 'var(--vscode-terminal-ansiYellow)' : 'var(--vscode-terminal-ansiGreen)'};"></div>
                    </div>
                    <div class="meter-detail" style="font-size: 11px; opacity: 0.8; margin-top: 2px;">expires: ${ttl.expiresAt.toLocaleTimeString()}</div>
                </div>
            `;
        }

        html += `
            <div class="meter-section" style="margin-bottom: 6px;">
                <div class="meter-row" style="display: flex; justify-content: space-between; font-size: 12px;">
                    <span>Latency</span>
                    <span>last: ${(lat.lastMs / 1000).toFixed(1)}s | avg: ${(lat.avgMs / 1000).toFixed(1)}s</span>
                </div>
            </div>
        `;

        if (summary.warnings.length > 0) {
            html += `
                <div class="meter-warnings" style="margin-top: 6px; padding: 4px 8px; background: var(--vscode-inputValidation-warningBackground); border-radius: 3px; font-size: 11px;">
                    ${summary.warnings.map(w => `<div>⚠️ ${w}</div>`).join('')}
                </div>
            `;
        }

        meterDiv.innerHTML = html;
    }
})();
