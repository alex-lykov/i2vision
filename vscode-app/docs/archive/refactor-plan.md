# Refactor Plan

> **Status: Superseded.**
> This plan is superseded by `docs/implementation-plan-tool-call-aggregation.md`
> and the shared tool-call helpers in `src/providers/common/toolCalls.ts`.

## Milestones

1. **Add fallback parser** – completed in `ThreeDLlmProvider`; extraction is now delegated to shared helpers.
2. **Update response handling** – completed in provider response normalization.
3. **Unit tests** – superseded; add `src/providers/__tests__/toolCalls.test.ts` for shared helper coverage.
4. **Manual verification** – pending.
5. **Documentation** – updated; see `flow-diagram.md` and `provider-architecture.md`.
6. **Code review & merge** – pending.

## Timeline (historical)

- **Day 1** – Implement fallback parser and response merge.
- **Day 2** – Write unit tests and run CI.
- **Day 3** – Manual verification and documentation update.
- **Day 4** – Review, address feedback, and merge.
