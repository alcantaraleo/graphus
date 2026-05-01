---
name: push-and-create-pr
description: Push the current branch and create a GitHub pull request with gh CLI. Use when the user explicitly asks to push, open a PR, create a pull request, or publish a branch for review in this project.
disable-model-invocation: true
---

# Push And Create PR

Use this skill only when the user explicitly asks to push or create a pull request.

## Scope

- Project-local workflow for this repository.
- Uses Git CLI and GitHub CLI (`gh`) only.
- Never pushes automatically unless the user asked for it in the current request.

## Safety Rules

1. Never change git config.
2. Never force-push unless the user explicitly requests it.
3. Never push directly to `main`/`master` unless the user explicitly requests it.
4. If current branch is `main`/`master`, ask the user before pushing.
5. Do not use interactive git flags.

## Workflow

1. Inspect branch and local state:
   - `git branch --show-current`
   - `git status --short`
   - `git log --oneline --decorate -n 10`
2. Extract issue number from branch name:
   - Branch naming convention: `feature/<issue-number>` or `bugfix/<issue-number>`.
   - Parse the numeric suffix after the last `/` (e.g. `feature/42` → `42`).
   - If no issue number is found, omit the closing keyword.
3. Verify if branch has an upstream:
   - `git rev-parse --abbrev-ref --symbolic-full-name @{u}`
   - If no upstream, push with `-u`.
4. Push branch:
   - Existing upstream: `git push`
   - No upstream: `git push -u origin HEAD`
5. Create PR body with this template (include `Closes` line only when an issue number was found):

```markdown
## Summary

- <bullet 1>
- <bullet 2>

## Test plan

- [ ] <test item 1>
- [ ] <test item 2>

Closes #<issue-number>
```

6. Create PR with `gh`:

```bash
gh pr create --title "<title>" --body "$(cat <<'EOF'
## Summary
- <bullet 1>
- <bullet 2>

## Test plan
- [ ] <test item 1>
- [ ] <test item 2>

Closes #<issue-number>
EOF
)"
```

7. Return PR URL and short summary of what was pushed.

## Title Guidance

- Follow conventional commit intent in PR title:
  - `feat(cli): ...`
  - `fix(parser): ...`
  - `chore(ci): ...`
- Keep it concise and review-friendly.

## Notes

- If commits are missing, ask user whether to commit first.
- If push or PR creation fails, report exact blocker and next command to run.
