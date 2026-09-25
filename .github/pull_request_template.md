<!--
Thanks for the PR. Fill in every section — delete the guidance text (in angle-bracket
comments like this one) as you go, but keep the headings so reviews stay consistent
across DataFlow / Insights / Platform / Goku.
-->

## Overview

<!-- One or two sentences: what does this PR do, in plain terms? -->



## Problem statement

<!-- What was broken, missing, or slow before this PR? Link an issue/outage/discussion
     if there is one. If this is new functionality rather than a fix, say what gap it
     closes and for whom. -->



## Proposed changes

<!-- The actual change, described at the level a reviewer needs to follow the diff —
     not a restatement of the file list. Call out anything non-obvious: a shared file
     you touched outside your own team's area, a new env var, a schema change. -->



## Testing steps

<!-- Exactly how you verified this — precise enough that a reviewer could repeat it.
     Include commands, not just "tested locally". -->

- [ ] `./mvnw test` (backend)
- [ ] `npm run build` (frontend)
- [ ] Manually verified: <!-- what you clicked through, and on which port/branch -->

## Blast radius

<!-- What can this break, and how far does it reach if it does? Be specific about pages,
     endpoints, and other teams' code — the Sep 11 outage happened because one service's
     failure silently took down two pages that called into it. -->

**Touches:**
- [ ] Categories / Holdings / Transactions (DataFlow)
- [ ] Overview / Insights / Action centre / EMI / Watchlist (Insights)
- [ ] External sources / Settings / Auth / FX / Market data (Platform)
- [ ] Goku (chat assistant)
- [ ] Shared frontend (`api.ts`, `types.ts`, `ui.tsx`, `util.ts`, `styles.css`, `App.tsx`)
- [ ] Shared backend (`ValuationService`, `MovementService`, `CsvService`)
- [ ] Database schema / migrations
- [ ] Build, CI, or deploy config

**If it fails:** <!-- what breaks, and does it fail loudly (an error the user sees) or
     silently (wrong numbers, a stale value)? -->

## Rollout strategy

<!-- How does this go out, and how does it come back out if it's wrong? -->

- [ ] Plain merge to `main` — no special sequencing needed
- [ ] Needs a specific merge order / depends on another open PR: <!-- which one -->
- [ ] Needs an env var or config change on deploy: <!-- name it -->
- [ ] Behind a flag / gated rollout: <!-- to whom, how it widens -->

**Rollback:** <!-- revert-and-redeploy, or is there a data change that makes rollback
     harder (a migration, a backfill)? -->
