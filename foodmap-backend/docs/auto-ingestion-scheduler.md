# AUTO ingestion — scheduled runs (Windows Task Scheduler)

PostgreSQL is FoodMap's runtime source of truth: the app reads restaurants
only from its database. Goong is only the **ingestion** source, reached only by
the snapshot runner. The scheduler simply starts that runner's AUTO mode once a
day; every decision stays inside the Java application.

## What the scheduler runs

Task Scheduler → `powershell.exe … -File foodmap-backend\scripts\auto-ingest.ps1`
→ one command, from the absolute working directory `foodmap-backend`:

```
java -jar target\foodmap-backend-1.0.0-SNAPSHOT.jar --spring.profiles.active=dev,snapshot-ingest
     --app.snapshot.auto=true --app.snapshot.budget=120 --app.snapshot.large=true --app.snapshot.execute=true
```

The wrapper only checks that the jar, `java.exe` and the database port exist
(failing fast with a clear log line otherwise), captures the output in a dated
log, and returns the application's exit code. It has no food/area, Goong,
budget, database or duplicate logic, never retries, and puts no secret on the
command line: the application reads `foodmap-backend\.env` (Goong keys) and the
environment itself.

## How AUTO protects the Goong budget

- **Ledger, 120 requests / rolling 24 h.** Every run's report in
  `ingestion-runs\` records the Goong HTTP attempts it made. Before any request
  AUTO sums the runs that finished in the last 24 h and spends at most
  `min(120, 120 − that usage)` — the "effective budget". If that is less than
  one probe (6 requests) plus a retry margin, it stops with `LEDGER_FULL`
  (exit 0) without calling Goong. An unreadable report makes it refuse (fail
  closed); a run that died counts its whole budget.
- **Hard per-request budget.** Every HTTP attempt (retries included) is
  charged before it is made; nothing outside an armed run can reach Goong.
  A 429 or a degraded provider stops the run.
- **One run at a time.** A PostgreSQL advisory lock: a second run exits with
  `LOCKED` (exit 2) and does nothing. Task Scheduler also never starts a second
  instance of the task.
- **Database identity and write checks.** AUTO only runs against
  `localhost:5434/foodmap_dev` and, after every wave, verifies that only the
  expected restaurant rows were inserted (`DB_SAFETY`, exit 2, otherwise).
- **Secrets.** Logs, reports and exceptions pass through the secret redactor
  ("Log secret redaction active" appears at the start of every log).

## Reports and logs

| What | Where |
|---|---|
| Run reports (also the ledger — never delete or edit them) | `foodmap-backend\ingestion-runs\snapshot-<time>.json` (+ `.md` summary) |
| Scheduler logs (wrapper lines + application output) | `foodmap-backend\ingestion-runs\logs\auto-<yyyyMMdd-HHmmss>.log` |

Scheduler logs older than 60 days are deleted by the wrapper
(`-LogRetentionDays`, 0 = keep). Reports are never touched. Both folders are
git-ignored.

## Exit codes ("Last Run Result")

| Code | Meaning | Action |
|---|---|---|
| 0 | completed, ledger full, no eligible work, max probes, or dry run | none |
| 3 | stopped early (budget, 429, degraded provider) or work pending | none — the next AUTO run resumes pending work first |
| 2 | refused, locked by another run, or database safety stop | read the log / report before the next run |
| 1 | application error | read the log |
| 10 / 11 / 12 / 13 | jar / java / database / log folder missing — nothing started | fix the prerequisite |

## Schedule

**Daily, once** (default 10:00), as long as the PC is on, logged in and Docker
Desktop runs (the task runs only while the user is logged on). The ledger is a
*rolling* 24 h window: a run that spent most of the 120 still counts a few
minutes past the next day's start, so the following daily run usually stops at
once with `LEDGER_FULL` (exit 0, no Goong call) and the day after spends a full
budget again — about one full run every two days. That is the intended
conservative start. To use more of the ceiling later, add a second daily
trigger 12 h apart (a full run every ~36 h); the application still caps every
24 h at 120.

## Enable (later, manually)

From `foodmap-backend\scripts` in PowerShell (as the user who runs Docker Desktop):

```powershell
# 1. Register the task DISABLED and inspect it in Task Scheduler (\FoodMap\FoodMap AUTO Ingest)
powershell -NoProfile -ExecutionPolicy Bypass -File .\register-auto-ingest-task.ps1
# 2. Enable it
Enable-ScheduledTask -TaskPath '\FoodMap\' -TaskName 'FoodMap AUTO Ingest'
```

The task: daily trigger; action `powershell.exe -NoProfile -NonInteractive
-ExecutionPolicy Bypass -WindowStyle Hidden -File "<backend>\scripts\auto-ingest.ps1"`
started in `<backend>`; "Run only when user is logged on"; "If the task is
already running: Do not start a new instance"; stop after 30 minutes; start as
soon as possible after a missed start; no "restart on failure".

## Disable / remove

```powershell
Disable-ScheduledTask -TaskPath '\FoodMap\' -TaskName 'FoodMap AUTO Ingest'
# or remove it entirely
Unregister-ScheduledTask -TaskPath '\FoodMap\' -TaskName 'FoodMap AUTO Ingest' -Confirm:$false
```

(Task Scheduler UI: Task Scheduler Library → FoodMap → right-click → Disable / Delete.)

## Run AUTO manually

```powershell
cd foodmap-backend
# preview: zero Goong requests, no database writes
powershell -NoProfile -ExecutionPolicy Bypass -File .\scripts\auto-ingest.ps1 -DryRun
# one real run (the same command the task runs)
powershell -NoProfile -ExecutionPolicy Bypass -File .\scripts\auto-ingest.ps1
```

Do not run it while the scheduled task is running (it would just exit `LOCKED`).

## When a scheduled run fails

1. Task Scheduler → the task → **Last Run Result** (exit code above).
2. The newest `ingestion-runs\logs\auto-*.log`: the `[wrapper]` lines say what
   ran, the exit code and the report file; a `NOT STARTED` line names a missing
   prerequisite (start Docker Desktop for code 12; rebuild the jar for 10).
3. The newest `ingestion-runs\snapshot-*.json` / `.md`: `stopReason`,
   `stopDetail`, the ledger before/after, every wave's DB check.
4. Never delete or edit reports to "free" budget — they are the ledger.
