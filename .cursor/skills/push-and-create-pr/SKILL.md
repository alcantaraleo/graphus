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
   - If no issue number is found, skip all issue-update steps below.
3. Verify if branch has an upstream:
   - `git rev-parse --abbrev-ref --symbolic-full-name @{u}`
   - If no upstream, push with `-u`.
4. Push branch:
   - Existing upstream: `git push`
   - No upstream: `git push -u origin HEAD`
5. Create PR body with this template (do NOT include a `Closes` line — issues are closed by the release workflow, not on merge):

```markdown
## Summary

- <bullet 1>
- <bullet 2>

## Test plan

- [ ] <test item 1>
- [ ] <test item 2>

Related to #<issue-number>
```

6. Create PR with `gh` and capture the PR URL:

```bash
PR_URL=$(gh pr create --title "<title>" --body "$(cat <<'EOF'
## Summary
- <bullet 1>
- <bullet 2>

## Test plan
- [ ] <test item 1>
- [ ] <test item 2>

Related to #<issue-number>
EOF
)")
echo "$PR_URL"
```

7. If an issue number was found, extract the PR number from the URL (trailing integer) and update the linked issue:
   - Add the `under-review` label: `gh issue edit <issue-number> --add-label "under-review"`
   - Post a tracking comment:

```bash
PR_NUMBER=$(echo "$PR_URL" | grep -oE '[0-9]+$')
gh issue edit <issue-number> --add-label "under-review"
gh issue comment <issue-number> --body "PR #${PR_NUMBER} is open for review. Issue will be closed when the next release is published."
```

8. Return PR URL and short summary of what was pushed.

## Title Guidance

- Follow conventional commit intent in PR title:
  - `feat(cli): ...`
  - `fix(parser): ...`
  - `chore(ci): ...`
- Keep it concise and review-friendly.

## Notes

- If commits are missing, ask user whether to commit first.
- If push or PR creation fails, report exact blocker and next command to run.
