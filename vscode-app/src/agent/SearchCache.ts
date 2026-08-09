/*
 * Copyright (c) 2026. Oleksii Lykov.
 *
 * Licensed under the MIT License.
 * SPDX-License-Identifier: MIT
 */

/**
 * SearchCache - Tracks search patterns to detect similar/repeated searches
 * and prevent the agent from looping on the same pattern.
 */

export class SearchCache {
  private _previousSearches: string[] = [];
  private _failedSearchCount: number = 0;
  private _lastSearchPattern: string | null = null;

  private static readonly SIMILAR_SEARCH_THRESHOLD = 0.5;

  /**
   * Normalize search pattern by removing regex special characters and converting to lowercase
   */
  normalizeSearchPattern(pattern: string): string {
    return pattern.replace(/[|.*+?^${}()|[\]\\]/g, '').toLowerCase();
  }

  /**
   * Check if current search pattern is similar to any previous search.
   * Returns the similar pattern if found, null otherwise.
   */
  findSimilarSearch(currentPattern: string): string | null {
    const normalizedCurrent = this.normalizeSearchPattern(currentPattern);

    for (const prevPattern of this._previousSearches) {
      const normalizedPrev = this.normalizeSearchPattern(prevPattern);

      const currentInPrev = normalizedPrev.includes(normalizedCurrent);
      const prevInCurrent = normalizedCurrent.includes(normalizedPrev);

      const currentTerms = normalizedCurrent.split(/[\s_]+/).filter(t => t.length > 2);
      const prevTerms = normalizedPrev.split(/[\s_]+/).filter(t => t.length > 2);

      const intersection = currentTerms.filter(t => prevTerms.includes(t));
      const union = [...new Set([...currentTerms, ...prevTerms])];
      const overlapRatio = union.length > 0 ? intersection.length / union.length : 0;

      if (currentInPrev || prevInCurrent || overlapRatio >= SearchCache.SIMILAR_SEARCH_THRESHOLD) {
        return prevPattern;
      }
    }

    return null;
  }

  /**
   * Record a search pattern for future similar-search detection.
   * Keeps only last 10 searches to avoid unbounded growth.
   */
  recordSearchPattern(pattern: string): void {
    if (this._previousSearches.length >= 10) {
      this._previousSearches.shift();
    }
    this._previousSearches.push(pattern);
  }

  get failedSearchCount(): number { return this._failedSearchCount; }
  set failedSearchCount(v: number) { this._failedSearchCount = v; }

  get lastSearchPattern(): string | null { return this._lastSearchPattern; }
  set lastSearchPattern(v: string | null) { this._lastSearchPattern = v; }

  /** For session serialization */
  getPreviousSearches(): readonly string[] { return this._previousSearches; }

  /** Deserialize from persisted state */
  restore(previousSearches: string[], failedSearchCount: number, lastSearchPattern: string | null): void {
    this._previousSearches = previousSearches.slice();
    this._failedSearchCount = failedSearchCount;
    this._lastSearchPattern = lastSearchPattern;
  }
}
