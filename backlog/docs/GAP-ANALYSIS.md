# Verbalization System: Gap Analysis & Action Plan

**Date:** 2025-01-XX  
**Author:** Critical review by external architect  
**Status:** Action items created as backlog tasks

---

## Executive Summary

The verbalization system is a **substantial, working implementation** with real performance benchmarks and comprehensive test coverage. However, **7 critical gaps** remain that could undermine production reliability and user trust.

---

## ✅ What's Working Well

| Area | Assessment |
|------|------------|
| **CNS-driven strategy selection** | Genuine innovation—self-tuning based on measurable neediness |
| **Multi-layer verbalizers** | All 5 VSLFC layers have dedicated verbalizers with typed contexts |
| **Three concrete strategies** | Real latencies measured: `<50ms`, `<200ms`, `<5000ms` |
| **Test coverage** | Comprehensive: all layers, CNS components, feedback, LLM integration |
| **Performance benchmarks** | All targets met: symbol coverage >95%, cache hit rate >80% |
| **Feedback system** | Functional JSONL persistence with corrections |

---

## ❗ Critical Gaps & Risks

### P0: Production Blockers

| # | Gap | Risk | Task |
|---|-----|------|------|
| **1** | **Cache invalidation undocumented** | Hash only includes symbol body—dependency changes NOT caught. Users get stale descriptions. | task-9 |
| **2** | **LEARNING strategy has no fallback** | LLM timeout/API error → silent failure. Production systems can't fail like this. | task-10 |
| **3** | **Batching/costs undocumented** | `<5000ms` only achievable with batching. No docs on prompt template, token costs, context window limits. | task-11 |

### P1: High Priority

| # | Gap | Risk | Task |
|---|-----|------|------|
| **4** | **Multi-Pass context undefined** | Currently only within-cluster. No cross-layer enrichment—output indistinguishable from INCREMENTAL. | task-12 |
| **5** | **Vision layer ambiguous** | Still root-README-only. All clusters get identical VisionContext (low value). | task-13 |

### P2: Medium Priority

| # | Gap | Risk | Task |
|---|-----|------|------|
| **6** | **CNS formula unvalidated** | Weights arbitrary (35/25/20/20). No correlation with actual LLM outcomes. | task-7 |
| **7** | **Feedback loop poisoning** | Single bad correction propagates team-wide. No scope, review, or expiry. | task-14 |

### P3: Nice-to-Have

| # | Gap | Risk | Task |
|---|-----|------|------|
| **8** | **Data flow disconnected** | Index Provider → Verbalization integration not documented. Confusing for new developers. | task-8 |

---

## 🎯 Action Plan

### Phase 1: Production Readiness (P0 Tasks)

**Goal:** Ensure system doesn't fail silently or serve stale data.

1. **task-9**: Document hash specification + implement context hash for dependency changes
2. **task-10**: Implement LEARNING → MULTI_PASS → INCREMENTAL fallback chain
3. **task-11**: Document batching strategy, prompt template, token costs

**Estimated effort:** 2-3 days  
**Risk if skipped:** Production failures, stale data, unpredictable costs

---

### Phase 2: Quality Improvements (P1 Tasks)

**Goal:** Make MULTI_PASS genuinely context-aware, clarify Vision limitations.

4. **task-12**: Extend Multi-Pass with cross-layer enrichment (Flow, Logic, Structure)
5. **task-13**: Document Vision input sources + propose per-cluster vision solutions

**Estimated effort:** 3-4 days  
**Risk if skipped:** MULTI_PASS underdelivers, users confused by identical Vision contexts

---

### Phase 3: Long-Term Reliability (P2 Tasks)

**Goal:** Validate CNS, prevent feedback corruption.

6. **task-7**: Design CNS calibration with telemetry hooks
7. **task-14**: Add feedback guardrails (scope, review, expiry, confidence decay)

**Estimated effort:** 2-3 days  
**Risk if skipped:** Arbitrary weights, team-wide description corruption

---

### Phase 4: Developer Experience (P3 Tasks)

**Goal:** Improve onboarding.

8. **task-8**: Add data-flow diagram connecting Index → Verbalization → MCP

**Estimated effort:** 0.5 days  
**Risk if skipped:** Confusing for new contributors

---

## 📊 Risk Matrix

| Risk | Likelihood | Impact | Mitigation |
|------|------------|--------|------------|
| Stale verbalizations (cache miss) | High | Medium | task-9 (context hash) |
| LLM API failure → no descriptions | Medium | High | task-10 (fallback chain) |
| Unexpected token costs | Medium | Medium | task-11 (cost docs) |
| MULTI_PASS = fancy INCREMENTAL | High | Low | task-12 (cross-layer) |
| VisionContext boilerplate | High | Low | task-13 (document limitation) |
| CNS weights wrong | Certain | Medium | task-7 (calibration plan) |
| Feedback poisoning | Low | High | task-14 (guardrails) |

---

## 📈 Success Metrics

After completing P0+P1 tasks:

- **Cache hit rate:** >80% (measured with context hash)
- **Fallback rate:** <5% (LEARNING → MULTI_PASS fallbacks)
- **Cross-layer references:** >50% of MULTI_PASS descriptions include Flow/Logic/Structure data
- **User satisfaction:** Feedback ratings >4.0/5.0 average

---

## 🔗 Related Backlog Tasks

| Task ID | Title | Priority |
|---------|-------|----------|
| task-9 | P0: Document hash input specification and validate cache invalidation | High |
| task-10 | P0: Add fallback chain for LEARNING strategy failures | High |
| task-11 | P0: Document batching strategy and token costs for LEARNING | High |
| task-12 | P1: Implement cross-layer enrichment for MULTI_PASS strategy | Medium |
| task-13 | P1: Document VisionVerbalizer input sources and limitations | Medium |
| task-7 | P2: Design CNS calibration plan with telemetry hooks | Medium |
| task-14 | P2: Add feedback guardrails (scope, review, expiry) | Medium |
| task-8 | P3: Add data-flow overview connecting Index → Verbalization → MCP | Low |

---

## 📝 Notes

This analysis was triggered by a critical external review of the verbalization system README. The reviewer noted:

> "This is a real, working system rather than a placeholder... But there are critical gaps that could undermine production reliability."

All gaps have been converted to actionable backlog tasks with clear acceptance criteria.

**Next step:** Prioritize P0 tasks for immediate implementation.
