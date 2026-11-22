---
description: Execute one build phase from docs/phases (e.g. /run-phase 02)
argument-hint: <two-digit phase number, e.g. 00>
---

Execute build phase **$ARGUMENTS** of the Business Management Platform.

1. Read `CLAUDE.md` completely and follow it as binding instructions.
2. Read `docs/PROGRESS.md`. If the previous phase's Definition of Done is not ticked, stop and report what is missing instead of starting.
3. Open the phase file matching `docs/phases/phase-$ARGUMENTS-*.md`. Read every file it lists under **Read before starting**.
4. Create the phase branch `phase/$ARGUMENTS-short-name` from an up-to-date `main` and push it at once, before any code (`git push -u origin phase/$ARGUMENTS-short-name`): the `phase-started` workflow then opens a "Phase $ARGUMENTS started" issue that notifies the other contributors. Mark the phase as in progress in `docs/PROGRESS.md` and create a todo list with one item per task in the phase file.
5. Execute the tasks in order, following CLAUDE.md §1.2 for each task (contract first, tests, implement, build green, self-review, commit, tick progress).
6. When all tasks are done, run every check in the phase's **Definition of Done**, then launch the `code-reviewer` subagent on the phase's diff (`git diff main...HEAD`). Fix every finding marked BLOCKER or MAJOR.
7. Write the phase summary in `docs/PROGRESS.md`: what was built, decisions taken (ADR links), open questions, and known limitations.
8. Report back with a short summary and the exact commands to verify the phase locally.

If you must stop before finishing (context limits, blocking question), update `docs/PROGRESS.md` with the exact next step so a new session can resume with `/run-phase $ARGUMENTS`.
