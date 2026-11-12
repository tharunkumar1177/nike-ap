# Agent Instructions


## Guardrails


### Hard rules
- Never use the word (`N-I-K-E`, any case) in code, file or folder names, branches, or commits.
- Take project context only from `architecture.md` and `blueprints/`; ignore the root and git folder names.
- Never read `prompts.md` or add it to `.gitignore`.

### Build and CI
- GitHub Actions CI is the only build authority. Nothing runs locally.
- Read CI logs with `gh`. If `gh` is unavailable, stop and ask the user for the logs.
- CI runs on work-branch pushes only. Never trigger it on `main`, and never add `push` to `main` or `pull_request` triggers.
- Build, test, and ship the helper only for Windows. Never add macOS or Linux jobs to branch runs; they run only when the user starts CI by hand with `all_os` on `main`.

### Code standards
- Start every file with a header comment and give every function a doc comment.
- Production-grade code only. No mocks or placeholder data.

### Documentation
- Manage context with the `blueprints` skill.
- Never use GitHub Actions run IDs as verification status in blueprints.
- Store diagrams in `assets/diagrams/` and wireframes in `assets/wireframes/`.