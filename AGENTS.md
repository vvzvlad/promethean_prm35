# Agent Instructions — promethean_prm35

Android app «Проектор» for the TV box GTKing_PRO (Android 9, API 28, rooted) that controls the
Promethean PRM-35 projector hanging off the box's Ethernet port: remote on the TV screen, the same
remote as a web page on port 8070, and an MCP server for the agent living on the box (tv-agent,
`projects/tv-agent` in the home-network repo). Plus the boot-time network setup on the box that
makes the projector reachable at all (`box/`).

What it does and where it lives on the box — `README.md`. Verified facts about the projector and
the box, decisions and rejected variants — `docs/SPEC.md`. Host pages in the Netmap wiki:
«GTKing_PRO 10.31.41.106» and «Promethean PRM-35 192.168.77.2».

## Structure

- `src/xyz/vvzvlad/projectorremote/` — Java: `Projector` (TCP client, lock, retry), `HttpServer`,
  `Mcp`, `RemoteService` (foreground service), `BootReceiver`, `MainActivity` (WebView)
- `assets/index.html` — the remote page (TV and phone); `assets/qrcode.js` — vendored, MIT
- `box/projector-net.sh`, `box/projector-net.rc` — network for the projector on the box
- `Dockerfile`, `Makefile` — build with plain SDK build-tools in Docker, no Gradle

## Build and install

```bash
make apk                          # build/projector-remote.apk, built on the docker daemon (cirunner)
make install TV=10.31.41.106:5555 # adb install -r and open the app
make logs
```

The docker CLI points at a remote daemon: no bind mounts, the APK comes back via `docker cp`.

## The live box

- The box is shared: tv-agent, mpv, mpv_sendspin, SendSpin, FakeAtv, JamesDSP run there. Opening
  the app (`make install`, `am start`) puts the remote over whatever is playing; what is on screen
  shows `dumpsys activity activities | grep mResumedActivity`.
- Port 8000 on the box's wlan0 is DNAT'd to the projector; the app uses 8070.

## Conventions

- Code comments, commit messages (subject and body) — English. UI strings — Russian.
- Routine through `make` targets.
- Default branch `main`.
