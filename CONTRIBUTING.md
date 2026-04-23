# Contributing to i²-Vision

Thanks for considering contributing! Here's what you need to know.

## Legal Stuff (One Click, Never Again)

i²-Vision is **MIT licensed**. By contributing, you agree that:

1. Your code is original work (you wrote it, you own it)
2. You license your contribution under the **MIT License**
3. You grant the project maintainers a license to use your contribution in future commercial extensions (SaaS, premium features, etc.)

**Why this exists:**
This ensures i²-Vision can sustainably offer both free open-source and optional paid features in the future—without restricting anyone's freedom to use the MIT-licensed core forever.

**How it works:**
When you open your first PR, a bot will ask you to click "I Agree." That's it. One click. Never again.

[Read the full CLA →](./CLA.md)

---

## Development Setup

```bash
git clone https://github.com/alex-lykov/i2vision/i2vision.git
cd i2vision
./gradlew build
```

## Before Submitting a PR

- [ ] Run `./gradlew test` and ensure all tests pass
- [ ] Run `./gradlew spotlessApply` to format code
- [ ] Add tests for new functionality
- [ ] Update documentation if needed

## Questions?

Open a [Discussion](https://github.com/alex-lykov/i2vision/i2vision/discussions) or issue.
