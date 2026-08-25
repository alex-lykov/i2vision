/*
 * Shared WebView Styles for i2-Vision Extension
 * Used by both Settings UI and Agent Tab (History) UI
 */

(function() {
    'use strict';

    /**
     * Get common VSCode WebView styles
     */
    function getCommonStyles() {
        return `
            /* ===== COMMON WEBVIEW STYLES ===== */
            body {
                font-family: var(--vscode-font-family);
                color: var(--vscode-foreground);
                background: var(--vscode-editor-background);
                padding: 0;
                margin: 0;
                line-height: 1.6;
            }

            /* Scrollbar styling */
            ::-webkit-scrollbar {
                width: 10px;
                height: 10px;
            }

            ::-webkit-scrollbar-track {
                background: var(--vscode-scrollbarSlider-background);
            }

            ::-webkit-scrollbar-thumb {
                background: var(--vscode-scrollbarSlider-hoverBackground);
                border-radius: 5px;
            }

            ::-webkit-scrollbar-thumb:hover {
                background: var(--vscode-scrollbarSlider-activeBackground);
            }

            /* Typography */
            h1, h2, h3, h4, h5, h6 {
                color: var(--vscode-foreground);
                font-weight: 600;
                margin-top: 24px;
                margin-bottom: 12px;
            }

            h1 { font-size: 1.5em; }
            h2 { font-size: 1.3em; }
            h3 { font-size: 1.1em; }

            p {
                margin: 8px 0;
            }

            /* Links */
            a {
                color: var(--vscode-textLink-foreground);
                text-decoration: none;
            }

            a:hover {
                text-decoration: underline;
                color: var(--vscode-textLink-activeForeground);
            }

            /* Code blocks */
            pre, code {
                font-family: var(--vscode-editor-font-family);
                font-size: 0.9em;
                background-color: var(--vscode-textCodeBlock-background);
                border-radius: 4px;
            }

            pre {
                padding: 12px;
                overflow-x: auto;
                margin: 12px 0;
            }

            code {
                padding: 2px 6px;
            }

            /* Forms and inputs */
            input[type="text"],
            input[type="number"],
            input[type="password"],
            select,
            textarea {
                width: 100%;
                padding: 6px 10px;
                background: var(--vscode-input-background);
                color: var(--vscode-input-foreground);
                border: 1px solid var(--vscode-input-border);
                border-radius: 4px;
                font-family: var(--vscode-font-family);
                font-size: 13px;
            }

            input:focus,
            select:focus,
            textarea:focus {
                outline: 1px solid var(--vscode-focusBorder);
                outline-offset: -1px;
            }

            input[type="checkbox"] {
                width: 16px;
                height: 16px;
                margin: 0;
                accent-color: var(--vscode-checkbox-background);
            }

            input[type="range"] {
                width: 100%;
                height: 4px;
                background: var(--vscode-input-background);
                border-radius: 2px;
                outline: none;
            }

            input[type="range"]::-webkit-slider-thumb {
                -webkit-appearance: none;
                appearance: none;
                width: 16px;
                height: 16px;
                background: var(--vscode-button-background);
                border-radius: 50%;
                cursor: pointer;
            }

            input[type="range"]::-webkit-slider-thumb:hover {
                background: var(--vscode-button-hoverBackground);
            }

            /* Buttons */
            button {
                padding: 6px 16px;
                border: none;
                border-radius: 4px;
                cursor: pointer;
                font-family: var(--vscode-font-family);
                font-size: 13px;
                font-weight: 500;
                transition: all 0.2s ease;
            }

            button.primary {
                background: var(--vscode-button-background);
                color: var(--vscode-button-foreground);
            }

            button.primary:hover {
                background: var(--vscode-button-hoverBackground);
            }

            button.secondary {
                background: var(--vscode-button-secondaryBackground);
                color: var(--vscode-button-secondaryForeground);
            }

            button.secondary:hover {
                background: var(--vscode-button-secondaryHoverBackground);
            }

            button:disabled {
                opacity: 0.5;
                cursor: not-allowed;
            }

            /* Badges */
            .badge {
                display: inline-flex;
                align-items: center;
                gap: 4px;
                padding: 2px 8px;
                border-radius: 12px;
                font-size: 11px;
                font-weight: 600;
                text-transform: uppercase;
            }

            .badge-info { background-color: #2196f320; color: #2196f3; border: 1px solid #2196f3; }
            .badge-success { background-color: #4caf5020; color: #4caf50; border: 1px solid #4caf50; }
            .badge-warning { background-color: #ff980020; color: #ff9800; border: 1px solid #ff9800; }
            .badge-error { background-color: #f4433620; color: #f44336; border: 1px solid #f44336; }

            /* Cards */
            .card {
                background: var(--vscode-editor-background);
                border: 1px solid var(--vscode-widget-border);
                border-radius: 6px;
                padding: 12px;
                margin: 8px 0;
            }

            .card-header {
                display: flex;
                align-items: center;
                gap: 8px;
                padding: 8px 0;
                border-bottom: 1px solid var(--vscode-widget-border);
                margin-bottom: 8px;
            }

            .card-title {
                font-weight: 600;
                font-size: 13px;
                color: var(--vscode-foreground);
            }

            .card-content {
                padding: 8px 0;
            }

            /* Utility classes */
            .text-muted {
                color: var(--vscode-descriptionForeground);
                font-size: 0.9em;
            }

            .text-error {
                color: var(--vscode-errorForeground);
            }

            .text-success {
                color: var(--vscode-terminal-ansiGreen);
            }

            .text-warning {
                color: var(--vscode-terminal-ansiYellow);
            }

            .mt-0 { margin-top: 0; }
            .mt-1 { margin-top: 4px; }
            .mt-2 { margin-top: 8px; }
            .mt-3 { margin-top: 16px; }
            .mt-4 { margin-top: 24px; }

            .mb-0 { margin-bottom: 0; }
            .mb-1 { margin-bottom: 4px; }
            .mb-2 { margin-bottom: 8px; }
            .mb-3 { margin-bottom: 16px; }
            .mb-4 { margin-bottom: 24px; }

            .flex { display: flex; }
            .flex-col { flex-direction: column; }
            .items-center { align-items: center; }
            .justify-between { justify-content: space-between; }
            .gap-1 { gap: 4px; }
            .gap-2 { gap: 8px; }
            .gap-3 { gap: 16px; }

            .hidden { display: none; }
            .block { display: block; }
            .inline-block { display: inline-block; }
        `;
    }

    // Export for use in other files
    if (typeof module !== 'undefined' && module.exports) {
        module.exports = { getCommonStyles };
    } else {
        window.getCommonStyles = getCommonStyles;
    }
})();
