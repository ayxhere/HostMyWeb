# Changes: multi-start + wish-service v2

## Multi-start (Projects screen)
- Projects → **Select** → tick projects → **Start N** hosts them all together.
- Card menu now has **Start hosting / Stop hosting** per project.
- First project that comes up is the "primary" (owns the Cloudflare tunnel; a named tunnel routes
  by hostname so one tunnel serves every project). A Cloudflare-enabled project is picked first.
- Others run beside it in their own runtime (static server / node process). Each needs its own port.
- Stopping the primary stops everything; stopping any other project only stops that one.
- Starting a selection replaces whatever was hosted before.
- Code: `HostController.startMany/stopOne`, `RuntimeManager.create`, `HostService.startMany`,
  `HostState.extras`, `ProjectsViewModel.startSelected/stopHosting`, `ProjectsScreen` selection UI.

## wish-service v2 (bundled asset `wish-service.zip.b64.00`)
- No external `unzip` binary anymore (pure JS zip reader) - the old build needed `unzip`, which
  Android app processes do not have.
- Upload streamed to disk instead of RAM (250 MB cap no longer risks an OOM kill).
- CORS for https://imayx.in, https://www.imayx.in, https://wish.imayx.in (+ OPTIONS preflight).
- JSON errors only, `/api/health`, per-request logs, build marker header `X-Wish-Build`.
- `WISH_BASE_URL` now defaults to https://wish.imayx.in (share links point at the wish host).
