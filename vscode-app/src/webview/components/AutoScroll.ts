/*
 * Copyright (c) 2026. Oleksii Lykov.
 *
 * Licensed under the MIT License.
 * SPDX-License-Identifier: MIT
 */

/**
 * AutoScroll — Smart stick-to-bottom behavior for chat/agent UI
 *
 * Attach to a scrollable container element. Automatically scrolls to
 * bottom when new content is added, but only if the user was already
 * near the bottom (within threshold). Shows a "scroll to bottom"
 * button when the user scrolls up.
 *
 * Usage:
 *   const scroller = new AutoScroll(document.getElementById('chat-container')!);
 *   scroller.attach();
 *   // After appending new content:
 *   scroller.onContentAdded();
 */

export class AutoScroll {
  private container: HTMLElement;
  private isNearBottom: boolean = true;
  private scrollButton: HTMLElement | null = null;
  private readonly threshold: number = 50; // px from bottom considered "near"
  private observer: MutationObserver | null = null;
  private scrollHandler: (() => void) | null = null;
  private resizeHandler: (() => void) | null = null;

  constructor(container: HTMLElement) {
    this.container = container;
  }

  /** Attach scroll listeners and create the scroll-to-bottom button */
  attach(): void {
    // Create floating scroll-to-bottom button
    this.scrollButton = document.createElement('button');
    this.scrollButton.className = 'auto-scroll-button';
    this.scrollButton.innerHTML = '↓';
    this.scrollButton.title = 'Scroll to bottom';
    this.scrollButton.style.cssText = `
      position: sticky;
      bottom: 12px;
      float: right;
      margin-right: 12px;
      width: 36px;
      height: 36px;
      border-radius: 50%;
      background: var(--vscode-button-background, #007acc);
      color: var(--vscode-button-foreground, white);
      border: none;
      cursor: pointer;
      font-size: 18px;
      display: none;
      z-index: 100;
      box-shadow: 0 2px 8px rgba(0,0,0,0.3);
      transition: opacity 0.2s;
    `;
    this.scrollButton.addEventListener('click', () => this.scrollToBottom());
    this.container.appendChild(this.scrollButton);

    // Track scroll position
    this.scrollHandler = () => this.checkScrollPosition();
    this.container.addEventListener('scroll', this.scrollHandler, { passive: true });

    // Re-check on resize
    this.resizeHandler = () => this.checkScrollPosition();
    window.addEventListener('resize', this.resizeHandler);

    // Initial check
    this.checkScrollPosition();
  }

  /** Call this after appending new content to the container */
  onContentAdded(): void {
    if (this.isNearBottom) {
      this.scrollToBottom();
    }
  }

  /** Scroll to bottom immediately (bypasses isNearBottom check) */
  scrollToBottom(): void {
    this.container.scrollTop = this.container.scrollHeight;
    this.isNearBottom = true;
    this.updateButtonVisibility();
  }

  /** Detach all listeners and clean up */
  detach(): void {
    if (this.scrollHandler) {
      this.container.removeEventListener('scroll', this.scrollHandler);
      this.scrollHandler = null;
    }
    if (this.resizeHandler) {
      window.removeEventListener('resize', this.resizeHandler);
      this.resizeHandler = null;
    }
    if (this.scrollButton) {
      this.scrollButton.remove();
      this.scrollButton = null;
    }
    if (this.observer) {
      this.observer.disconnect();
      this.observer = null;
    }
  }

  private checkScrollPosition(): void {
    const { scrollTop, scrollHeight, clientHeight } = this.container;
    this.isNearBottom = scrollHeight - scrollTop - clientHeight < this.threshold;
    this.updateButtonVisibility();
  }

  private updateButtonVisibility(): void {
    if (!this.scrollButton) return;
    this.scrollButton.style.display = this.isNearBottom ? 'none' : 'block';
  }
}
