/*
 * Copyright (c) 2026. Oleksii Lykov.
 *
 * Licensed under the MIT License.
 * SPDX-License-Identifier: MIT
 */

/**
 * WebView JavaScript for i2-Vision Agent Tab
 * Modern UI/UX with clean design, collapsible cards, and enhanced actions
 */

(function() {
    'use strict';

    // Initialize when DOM is ready
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

        let currentProgressDiv = null;
        let isProcessing = false;
        let streamingMessageDiv = null;

        // Load configuration from HTML data attributes
        const htmlEl = document.documentElement;
        const outputSettings = htmlEl.dataset.outputSettings ? JSON.parse(htmlEl.dataset.outputSettings) : {};
        const providerId = htmlEl.dataset.providerId || 'ollama';
        const modelId = htmlEl.dataset.modelId || '';
        
        if (outputSettings.theme && outputSettings.theme !== 'system') {
            document.body.classList.add('theme-' + outputSettings.theme);
        }
        if (outputSettings.fontSize) {
            document.body.classList.add('font-' + outputSettings.fontSize);
        }
        window.outputSettings = outputSettings;
        window.PROVIDER_ID = providerId;
        window.MODEL_ID = modelId;
        
        console.log('[WebView] Config loaded:', { providerId, modelId });

        // Model definitions
        const MODELS_BY_PROVIDER = {
            'ollama': [
                { id: 'llama3.2:3b', name: 'Llama 3.2 3B (Local)', type: 'local', quality: 'good' },
                { id: 'llama3.2:7b', name: 'Llama 3.2 7B (Local)', type: 'local', quality: 'good' },
                { id: 'codellama:7b', name: 'CodeLlama 7B (Local)', type: 'local', quality: 'good' },
                { id: 'qwen3.5:cloud', name: 'Qwen 3.5 (Cloud)', type: 'cloud', quality: 'excellent' },
                { id: 'deepseek-v3.1:671b-cloud', name: 'DeepSeek V3.1 (Cloud)', type: 'cloud', quality: 'excellent' }
            ],
            'deepseek': [
                { id: 'deepseek-chat', name: 'DeepSeek Chat (V3)', type: 'cloud', quality: 'excellent' },
                { id: 'deepseek-coder', name: 'DeepSeek Coder', type: 'cloud', quality: 'excellent' },
                { id: 'deepseek-reasoner', name: 'DeepSeek Reasoner (R1)', type: 'cloud', quality: 'excellent' }
            ]
        };

        // Initialize model dropdown
        initializeModelDropdown();

        // Event handlers
        window.onProviderChange = function() {
            vscode.postMessage({ command: 'changeProvider', provider: providerSelect.value });
            initializeModelDropdown();
        };

        window.onModelChange = function() {
            vscode.postMessage({ command: 'changeModel', model: modelSelect.value });
        };

        window.addEventListener('error', function(event) {
            console.error('WebView error:', event.error);
            isProcessing = false;
            userInput.disabled = false;
            updateActionButton();
        });

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

        window.stopAgent = function() {
            vscode.postMessage({ command: 'stopAgent' });
        };

        // Expose functions globally
        window.updateActionButton = updateActionButton;
        window.addMessage = addMessage;
        window.escapeHtml = escapeHtml;
        window.showProgress = showProgress;
        window.hideProgress = hideProgress;
        window.formatDuration = formatDuration;
        window.getPreviewText = getPreviewText;
        window.formatResponseText = formatResponseText;
        window.createOutputCard = createOutputCard;
        window.toggleOutputCard = toggleOutputCard;
        window.toggleToolCallCard = toggleToolCallCard;
        window.handleFooterAction = handleFooterAction;
        window.openSettings = openSettings;

        // Message handler
        window.addEventListener('message', function(event) {
            handleMessage(event.data);
        });

        updateActionButton();

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

            if (currentProgressDiv) {
                currentProgressDiv.remove();
                currentProgressDiv = null;
            }

            streamingMessageDiv = document.createElement('div');
            streamingMessageDiv.className = 'message agent-message';
            streamingMessageDiv.innerHTML = '<em class="text-muted">Thinking...</em>';
            messagesDiv.appendChild(streamingMessageDiv);
            messagesDiv.scrollTop = messagesDiv.scrollHeight;

            vscode.postMessage({ command: 'sendMessage', text });
            userInput.value = '';
        }

        function updateActionButton() {
            if (isProcessing) {
                actionButton.textContent = 'Stop';
                actionButton.className = 'stop-button';
            } else {
                actionButton.textContent = 'Send';
                actionButton.className = '';
            }
        }

        function addMessage(type, content, isHtml) {
            const div = document.createElement('div');
            div.className = 'message ' + (type === 'user' ? 'user-message' : 'agent-message');
            if (isHtml) div.innerHTML = content;
            else div.textContent = content;
            messagesDiv.appendChild(div);
            messagesDiv.scrollTop = messagesDiv.scrollHeight;
        }

        function escapeHtml(text) {
            if (!text) return '';
            const div = document.createElement('div');
            div.textContent = text;
            return div.innerHTML;
        }

        function showProgress(message) {
            if (currentProgressDiv) currentProgressDiv.remove();
            currentProgressDiv = document.createElement('div');
            currentProgressDiv.className = 'progress-indicator';
            currentProgressDiv.innerHTML = '<div class="spinner"></div><span>' + message + '</span>';
            messagesDiv.appendChild(currentProgressDiv);
            messagesDiv.scrollTop = messagesDiv.scrollHeight;
        }

        function hideProgress() {
            if (currentProgressDiv) {
                currentProgressDiv.remove();
                currentProgressDiv = null;
            }
        }

        function formatDuration(ms) {
            if (ms < 1000) return ms + 'ms';
            return (ms / 1000).toFixed(1) + 's';
        }

        function getPreviewText(text, maxLines) {
            const lines = text.split('\n');
            if (lines.length <= maxLines) return text;
            return lines.slice(0, maxLines).join('\n') + '\n\n... (expand to show more)';
        }

        function formatResponseText(text) {
            if (!text) return '<em class="text-muted">No response generated.</em>';
            let formatted = escapeHtml(text);
            formatted = formatted.replace(/\n/g, '<br>');
            formatted = formatted.replace(/\*\*([^*]+)\*\*/g, '<strong>$1</strong>');
            // Code blocks: ```lang ... ```
            formatted = formatted.replace(/```(\w*)\n([\s\S]*?)```/g, function(match, lang, code) {
                return '<pre class="code-block"><code class="language-' + lang + '">' + escapeHtml(code.trim()) + '</code></pre>';
            });
            // Inline code: `code`
            formatted = formatted.replace(/`([^`]+)`/g, '<code class="inline-code">$1</code>');
            return formatted;
        }

        // =====================================================================
        // OUTPUT CARD CREATION (Main Card: Header + Content + Footer)
        // =====================================================================

        function createOutputCard(card) {
            const cardDiv = document.createElement('div');
            cardDiv.className = 'agent-output-card';
            cardDiv.dataset.cardId = 'card-' + Date.now();
            
            const providerColor = card.header.provider === 'ollama' ? 'var(--vscode-terminal-ansiGreen)' : 'var(--vscode-terminal-ansiBlue)';
            const statusIcon = card.header.status === 'success' ? '✓' : '✗';
            
            let html = '';
            
            // --- HEADER ---
            html += '<div class="output-card-header">';
            html += '  <div class="header-left">';
            html += '    <span class="provider-badge" style="background-color: ' + providerColor + '">' + escapeHtml(card.header.providerName) + '</span>';
            html += '    <span class="model-name">' + escapeHtml(card.header.model) + '</span>';
            html += '  </div>';
            html += '  <div class="header-right">';
            html += '    <span class="status-badge status-' + card.header.status + '">' + statusIcon + '</span>';
            html += '    <span class="metric-badge">' + formatDuration(card.header.durationMs) + '</span>';
            html += '    <span class="metric-badge">' + card.header.iterations + ' iter</span>';
            html += '  </div>';
            html += '</div>';
            
            // --- CONTENT ---
            html += '<div class="output-card-content">';
            
            // Error section (if failed)
            if (card.content.error) {
                html += '<div class="error-section">';
                html += '  <span class="error-icon">✗</span>';
                html += '  <span class="error-text">' + escapeHtml(card.content.error) + '</span>';
                html += '</div>';
            }
            
            // Reasoning section (if available and enabled)
            if (card.content.reasoning && card.display.showReasoning) {
                html += '<div class="reasoning-section">';
                html += '  <div class="section-header" onclick="toggleReasoning(this)">';
                html += '    <span class="section-icon">💭</span>';
                html += '    <span class="section-label">Reasoning</span>';
                html += '    <span class="section-toggle">▼</span>';
                html += '  </div>';
                html += '  <div class="reasoning-content">';
                html += '    <div class="reasoning-text">' + formatResponseText(card.content.reasoning) + '</div>';
                html += '  </div>';
                html += '</div>';
            }
            
            // Response text section (collapsible by default)
            const shouldCollapse = card.content.text.length > card.display.autoCollapseAfter;
            const previewText = shouldCollapse && card.display.collapsed 
                ? getPreviewText(card.content.text, card.display.maxPreviewLines)
                : card.content.text;
            
            html += '<div class="response-text-section' + (shouldCollapse && card.display.collapsed ? ' collapsed' : '') + '">';
            html += '  <div class="response-text">' + formatResponseText(previewText) + '</div>';
            if (shouldCollapse) {
                const expandText = card.display.collapsed ? 'Show full response' : 'Show less';
                html += '  <button class="expand-button" onclick="toggleOutputCard(this)">';
                html += '    <span class="expand-text">' + expandText + '</span>';
                html += '  </button>';
            }
            html += '</div>';
            
            // Tool calls section (if available and enabled)
            if (card.display.showToolDetails && card.content.toolCalls && card.content.toolCalls.length > 0) {
                html += '<div class="tool-section">';
                html += '  <div class="section-header">';
                html += '    <span class="section-icon">⚡</span>';
                html += '    <span class="section-label">Tool Calls (' + card.content.toolCalls.length + ')</span>';
                html += '  </div>';
                html += '  <div class="tool-calls-list">';
                card.content.toolCalls.forEach(function(tc) {
                    const successClass = tc.success !== false ? 'success' : 'error';
                    const icon = tc.success !== false ? '✓' : '✗';
                    html += '    <div class="tool-call-card ' + successClass + '">';
                    html += '      <div class="tool-call-header" onclick="toggleToolCallCard(this)">';
                    html += '        <span class="tool-call-toggle">▶</span>';
                    html += '        <span class="tool-call-status">' + icon + '</span>';
                    html += '        <span class="tool-call-name">' + escapeHtml(tc.toolName) + '</span>';
                    html += '        <span class="tool-call-meta">';
                    if (tc.durationMs) {
                        html += '      <span class="tool-call-duration">' + formatDuration(tc.durationMs) + '</span>';
                    }
                    html += '        </span>';
                    html += '      </div>';
                    html += '      <div class="tool-call-body" style="display: none;">';
                    if (tc.args && Object.keys(tc.args).length > 0) {
                        html += '        <div class="tool-call-args">';
                        html += '          <span class="args-label">Arguments</span>';
                        html += '          <pre><code>' + syntaxHighlight(JSON.stringify(tc.args, null, 2)) + '</code></pre>';
                        html += '        </div>';
                    }
                    if (tc.result) {
                        html += '        <div class="tool-call-result">';
                        html += '          <span class="result-label">Result</span>';
                        html += '          <pre><code>' + syntaxHighlight(tc.result) + '</code></pre>';
                        html += '        </div>';
                    }
                    if (tc.error) {
                        html += '        <div class="tool-call-error">';
                        html += '          <span class="error-label">Error</span>';
                        html += '          <span>' + escapeHtml(tc.error) + '</span>';
                        html += '        </div>';
                    }
                    html += '      </div>';
                    html += '    </div>';
                });
                html += '  </div>';
                html += '</div>';
            }
            
            html += '</div>'; // End content
            
            // --- FOOTER ---
            html += '<div class="output-card-footer">';
            
            // Meta information (tokens, confidence)
            const metaItems = [];
            if (card.footer.tokensUsed) {
                metaItems.push('<span class="meta-item">📊 ' + card.footer.tokensUsed + ' tokens</span>');
            }
            if (card.footer.confidence) {
                metaItems.push('<span class="meta-item">🎯 ' + Math.round(card.footer.confidence * 100) + '% confidence</span>');
            }
            if (metaItems.length > 0) {
                html += '<div class="footer-meta">' + metaItems.join('') + '</div>';
            }
            
            // Action buttons
            html += '<div class="footer-actions">';
            html += '  <button class="footer-action-btn" onclick="handleFooterAction(\'copy\')" title="Copy response">';
            html += '    <span class="action-label">Copy</span>';
            html += '  </button>';
            html += '  <button class="footer-action-btn" onclick="handleFooterAction(\'apply\')" title="Apply to file">';
            html += '    <span class="action-label">Apply</span>';
            html += '  </button>';
            html += '  <button class="footer-action-btn" onclick="handleFooterAction(\'explain\')" title="Explain this">';
            html += '    <span class="action-label">Explain</span>';
            html += '  </button>';
            html += '  <button class="footer-action-btn" onclick="handleFooterAction(\'retry\')" title="Retry">';
            html += '    <span class="action-label">Retry</span>';
            html += '  </button>';
            html += '  <button class="footer-action-btn settings-btn" onclick="openSettings()" title="Settings">';
            html += '    <span class="action-icon">⚙</span>';
            html += '  </button>';
            html += '</div>';
            
            html += '</div>'; // End footer
            
            cardDiv.innerHTML = html;
            return cardDiv;
        }
        
        // =====================================================================
        // INTERACTION HANDLERS
        // =====================================================================
        
        function toggleOutputCard(button) {
            const section = button.closest('.response-text-section');
            const text = button.querySelector('.expand-text');
            
            if (section.classList.contains('collapsed')) {
                section.classList.remove('collapsed');
                text.textContent = 'Show less';
            } else {
                section.classList.add('collapsed');
                text.textContent = 'Show full response';
            }
        }
        
        function toggleReasoning(header) {
            const content = header.nextElementSibling;
            const toggle = header.querySelector('.section-toggle');
            if (content.style.display === 'none') {
                content.style.display = 'block';
                toggle.textContent = '▼';
            } else {
                content.style.display = 'none';
                toggle.textContent = '▶';
            }
        }
        
        function toggleToolCallCard(header) {
            const body = header.nextElementSibling;
            const toggle = header.querySelector('.tool-call-toggle');
            if (body.style.display === 'none') {
                body.style.display = 'block';
                toggle.textContent = '▼';
            } else {
                body.style.display = 'none';
                toggle.textContent = '▶';
            }
        }
        
        function handleFooterAction(actionId) {
            console.log('Footer action:', actionId);
            
            if (actionId === 'copy') {
                const lastCard = messagesDiv.querySelector('.agent-output-card:last-child .response-text');
                if (lastCard) {
                    navigator.clipboard.writeText(lastCard.textContent);
                    showTemporaryFeedback('Copied to clipboard');
                }
            } else if (actionId === 'apply') {
                vscode.postMessage({ command: 'applyToFile' });
                showTemporaryFeedback('Apply to file...');
            } else if (actionId === 'explain') {
                const lastCard = messagesDiv.querySelector('.agent-output-card:last-child .response-text');
                if (lastCard) {
                    const text = lastCard.textContent;
                    userInput.value = 'Explain this: ' + text.substring(0, 200) + (text.length > 200 ? '...' : '');
                    sendMessage();
                }
            } else if (actionId === 'retry') {
                const lastUserMsg = messagesDiv.querySelector('.user-message:last-child');
                if (lastUserMsg) {
                    userInput.value = lastUserMsg.textContent;
                    sendMessage();
                }
            }
        }
        
        function openSettings() {
            vscode.postMessage({ command: 'openSettings' });
        }
        
        function showTemporaryFeedback(message) {
            const feedback = document.createElement('div');
            feedback.className = 'temporary-feedback';
            feedback.textContent = message;
            document.body.appendChild(feedback);
            setTimeout(function() {
                feedback.classList.add('fade-out');
                setTimeout(function() { feedback.remove(); }, 300);
            }, 2000);
        }
        
        // =====================================================================
        // SYNTAX HIGHLIGHTING FOR CODE
        // =====================================================================
        
        function syntaxHighlight(code) {
            if (!code) return '';
            // Simple syntax highlighting for JSON and code
            let highlighted = escapeHtml(code);
            // Strings
            highlighted = highlighted.replace(/"([^"]*)"/g, '<span class="code-string">"$1"</span>');
            // Numbers
            highlighted = highlighted.replace(/\b(\d+)\b/g, '<span class="code-number">$1</span>');
            // Keywords
            highlighted = highlighted.replace(/\b(function|return|if|else|for|while|const|let|var|class|import|export|from|async|await)\b/g, '<span class="code-keyword">$1</span>');
            // Booleans
            highlighted = highlighted.replace(/\b(true|false|null|undefined)\b/g, '<span class="code-boolean">$1</span>');
            return highlighted;
        }
        
        // =====================================================================
        // MODEL DROPDOWN INITIALIZATION
        // =====================================================================

        function initializeModelDropdown() {
            const currentProvider = providerSelect.value;
            const models = MODELS_BY_PROVIDER[currentProvider] || [];
            const expectedModelId = window.MODEL_ID || '';
            
            console.log('[WebView] Initializing dropdown - provider:', currentProvider);
            console.log('[WebView] Expected model ID:', expectedModelId);
            console.log('[WebView] Available models:', models);
            
            modelSelect.innerHTML = '';
            
            if (!models || models.length === 0) {
                console.error('[WebView] No models found for provider:', currentProvider);
                const errorOption = document.createElement('option');
                errorOption.textContent = 'No models available';
                errorOption.disabled = true;
                modelSelect.appendChild(errorOption);
                return;
            }
            
            const localModels = models.filter(m => m.type === 'local');
            const cloudModels = models.filter(m => m.type === 'cloud');
            
            let hasSelected = false;
            
            if (localModels.length > 0) {
                const localOptgroup = document.createElement('optgroup');
                localOptgroup.label = 'Local Models';
                localModels.forEach(function(model) {
                    const option = document.createElement('option');
                    option.value = model.id;
                    option.textContent = model.name;
                    if (!hasSelected && model.id === expectedModelId) {
                        option.selected = true;
                        hasSelected = true;
                    }
                    localOptgroup.appendChild(option);
                });
                modelSelect.appendChild(localOptgroup);
            }
            
            if (cloudModels.length > 0) {
                const cloudOptgroup = document.createElement('optgroup');
                cloudOptgroup.label = 'Cloud Models';
                cloudModels.forEach(function(model) {
                    const option = document.createElement('option');
                    option.value = model.id;
                    option.textContent = model.name;
                    if (!hasSelected && model.id === expectedModelId) {
                        option.selected = true;
                        hasSelected = true;
                    }
                    cloudOptgroup.appendChild(option);
                });
                modelSelect.appendChild(cloudOptgroup);
            }
            
            if (!hasSelected && modelSelect.options.length > 0) {
                modelSelect.options[0].selected = true;
            }
            
            console.log('[WebView] Dropdown initialized - total options:', modelSelect.options.length);
            console.log('[WebView] Selected value:', modelSelect.value);
        }

        // =====================================================================
        // MESSAGE HANDLER
        // =====================================================================

        function handleMessage(message) {
            try {
                switch (message.command) {
                    case 'processing':
                        showProgress('Processing: ' + message.userInput.substring(0, 50) + '...');
                        break;

                    case 'streamingText':
                        if (streamingMessageDiv) {
                            const cleanText = message.accumulated
                                .replace(/\n/g, '<br>')
                                .replace(/\*\*([^*]+)\*\*/g, '<strong>$1</strong>');
                            streamingMessageDiv.innerHTML = cleanText;
                            messagesDiv.scrollTop = messagesDiv.scrollHeight;
                            console.log('[WebView] Streaming text updated:', message.accumulated.length, 'chars');
                        }
                        break;

                    case 'progress':
                        const evt = message.event;
                        if (evt.type === 'thinking') showProgress(evt.message);
                        break;

                    case 'configUpdated':
                        if (message.provider) providerSelect.value = message.provider;
                        if (message.model) {
                            initializeModelDropdown();
                            modelSelect.value = message.model;
                        }
                        showProgress('Configuration updated');
                        setTimeout(hideProgress, 2000);
                        break;

                    case 'response':
                        hideProgress();

                        if (streamingMessageDiv) {
                            streamingMessageDiv.remove();
                            streamingMessageDiv = null;
                        }

                        console.log('[WebView] Received final response:', message.response?.text?.length, 'chars');

                        if (window.createOutputCard && message.response && message.response.text) {
                            const settings = window.outputSettings || {};
                            const cardData = {
                                header: {
                                    provider: window.PROVIDER_ID || 'ollama',
                                    providerName: (window.PROVIDER_ID || 'ollama') === 'ollama' ? 'Ollama' : 'DeepSeek',
                                    model: window.MODEL_ID || '',
                                    timestamp: Date.now(),
                                    durationMs: message.response.durationMs,
                                    iterations: message.response.iterations,
                                    status: message.response.success !== false ? 'success' : 'error'
                                },
                                content: {
                                    text: message.response.text,
                                    reasoning: message.response.reasoning, // New: AI reasoning
                                    toolCalls: (message.response.toolCards || []).map(function(tc) {
                                        return {
                                            toolName: tc.toolName,
                                            args: tc.args || {},
                                            result: tc.result,
                                            durationMs: tc.durationMs,
                                            success: !tc.error,
                                            error: tc.error
                                        };
                                    }),
                                    buildOutput: message.response.buildOutput,
                                    error: message.response.success === false ? 'Request failed' : undefined
                                },
                                footer: {
                                    tokensUsed: settings.showTokenCount ? message.response.tokensUsed : undefined,
                                    confidence: settings.showConfidence ? message.response.confidence : undefined,
                                    actions: [
                                        { id: 'copy', label: 'Copy', enabled: true },
                                        { id: 'apply', label: 'Apply', enabled: true },
                                        { id: 'explain', label: 'Explain', enabled: true },
                                        { id: 'retry', label: 'Retry', enabled: true }
                                    ]
                                },
                                display: {
                                    collapsed: true, // Always collapse by default
                                    showReasoning: settings.showReasoning || false,
                                    showToolDetails: settings.showToolDetails !== false,
                                    showTokenCount: settings.showTokenCount || false,
                                    showConfidence: settings.showConfidence || false,
                                    theme: settings.theme || 'system',
                                    fontSize: settings.fontSize || 'medium',
                                    autoCollapseAfter: settings.autoCollapse || 300, // More aggressive collapsing
                                    maxPreviewLines: settings.maxPreviewLines || 5, // Show fewer lines
                                    codeHighlight: settings.codeHighlight !== false
                                }
                            };
                            
                            const cardDiv = window.createOutputCard(cardData);
                            messagesDiv.appendChild(cardDiv);
                            messagesDiv.scrollTop = messagesDiv.scrollHeight;
                            console.log('[WebView] Final card displayed');
                            
                            setTimeout(function() {
                                messagesDiv.scrollTop = messagesDiv.scrollHeight;
                            }, 50);
                        }

                        isProcessing = false;
                        updateActionButton();
                        userInput.disabled = false;
                        userInput.focus();
                        break;

                    case 'stopped':
                        hideProgress();
                        if (streamingMessageDiv) {
                            streamingMessageDiv.remove();
                            streamingMessageDiv = null;
                        }
                        addMessage('agent', '<strong>⏹ Stopped by user</strong>', true);
                        isProcessing = false;
                        updateActionButton();
                        userInput.disabled = false;
                        userInput.focus();
                        break;

                    case 'error':
                        hideProgress();
                        if (streamingMessageDiv) {
                            streamingMessageDiv.remove();
                            streamingMessageDiv = null;
                        }
                        addMessage('agent', 'Error: ' + message.error, false);
                        isProcessing = false;
                        userInput.disabled = false;
                        break;
                }
            } catch (error) {
                console.error('Error handling message:', error, message);
                isProcessing = false;
                userInput.disabled = false;
                updateActionButton();
            }
        }
    }
})();
