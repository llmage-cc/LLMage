# Operations

A running server needs four things looked after: the log, the disk, the replay directory, and the
upgrade path. This page gives the commands and procedures for each, including the retention and
migration scripts that ship with the repository and the card-database behaviour that decides
whether a fresh deploy serves cards at all.

> **Who this is for:** the person responsible for keeping a deployed server healthy and recoverable.

## Routine checks

```bash
pgrep -af 'mage.server.Main'                 # server process
pgrep -af 'mage-web-proxy'                   # proxy process
ss -ltn | grep -E ':(17171|8090)\b'          # both ports listening
tail -n 100 mageserver.log                   # recent server activity
du -sh saved gamelogs db oldlogs             # growth
```

A healthy start ends with `Started MAGE server - listening on ...`. A server process that exists
without that line in the log is either still scanning cards or failed before binding the socket;
read the log from the top rather than the tail.

## Logs

| File | Written by | Contents |
|---|---|---|
| `mageserver.log` | Server, when its log4j configuration wins the classpath | Startup, configuration echo, game lifecycle, LLM errors, admin actions. |
| `mageadmin.log` | Headless console runner | Output from the console process when that runner is used. |
| `mageclient.log` | Desktop client | Client-side errors, written next to the client. |
| `pipeline.log` | Automated game pipeline (test tooling) | Pipeline stages and their exit codes. |
| `gamelogs/game_<id>/` | LLM seats | One transcript per seat, plus post-game files. |
| `gamelogsJson/` | Clients that request JSON logs | Machine-readable game logs. |
| Proxy console output | Web proxy | WebSocket connects, rejected origins, relay errors. |

The proxy writes to standard output unless you give it a log4j configuration of its own. Under
systemd that output goes to the journal:

```bash
journalctl -u llmage-proxy -n 100 --no-pager
```

If `mageserver.log` is missing while the server process is running, check for a web proxy JAR in
`Mage.Server/release/lib/` first. Its logging configuration shadows the server's, and the server
logs to the console instead. Move the proxy JAR to `release/plugins/` and restart.

### Rotation

`scripts/archive-logs.sh` moves the current log files and per-game directories into `oldlogs/`
with a timestamp suffix. It never deletes anything, and it skips entries that are empty or absent.
`start-server.sh` runs it on every start, so a restart is also a rotation.

For long-running servers that are not restarted often, add size- or date-based rotation in
`config/log4j.properties`. The sample file already contains a size-rolled appender (10 MB per file,
five backups) and a daily-rolled appender; name the one you want on `log4j.rootLogger` and enable
the file with `-Dlog4j.configuration=file:./config/log4j.properties`.

`logrotate` also works, with one caveat: use `copytruncate`, because log4j holds the file open and
a plain rename leaves the process writing to the moved file.

## Monitoring

Two scripts cover the common checks. Both were written for LLM-versus-LLM game runs; their log
checks are useful on any server.

| Script | Mode | Reports |
|---|---|---|
| `scripts/monitor-games.sh` | One-shot | Server presence, active game runners, FATAL and error counts, and per-game transcript age, size, latest turn, and post-game file count. Warns when a transcript has not changed for fifteen minutes. |
| `scripts/monitor-watch.sh` | Background loop, 300-second interval | The same stall signal plus new error lines appended to the server log since the previous pass. Exits when no game runners remain. |

`monitor-games.sh` exits non-zero when the server is not running, and with a distinct code when
the log file is missing while the process is up. Both scripts treat a missing log as a failure
rather than reporting zero errors from a file that does not exist.

Run the one-shot version from cron for a daily summary, and the watcher while a batch of games is
in progress:

```bash
scripts/monitor-games.sh
scripts/monitor-watch.sh &
```

Neither script restarts anything. A stall warning means "look at the transcript and the log", not
"kill the server".

### A health check for an external monitor

```bash
#!/usr/bin/env bash
set -euo pipefail
pgrep -f 'mage.server.Main' >/dev/null
curl -fsS http://127.0.0.1:8090/config >/dev/null
grep -q 'Started MAGE server' mageserver.log
```

Exit status is the signal. Point the monitor at this script rather than at a TCP port, because a
bound port says nothing about whether the game server finished starting.

## Backups

There are two different backups, and confusing them loses data.

**Source backup.** `scripts/backup.sh [output_dir]` writes a zstd-compressed tarball of the
checkout, excluding `.git`, plugin images, build outputs, the release tree, logs, game logs,
`oldlogs/`, `saved/`, and `db/`. It requires a git checkout, names the file `llmage-<date>.tar.zst`
in the directory you pass, and defaults to a directory under the operator's home. It is a
convenience for source changes, not a data backup.

**Data backup.** These directories hold everything that cannot be rebuilt:

| Path | Why it matters |
|---|---|
| `db/` | Accounts, stats, table records, and the card database. |
| `saved/` | Replays and their metadata sidecars. |
| `gamelogs/` | LLM transcripts and post-game analysis. |
| The active `config.xml` | Ports, limits, mail, and LLM defaults. |

```bash
STAMP=$(date +%Y%m%d_%H%M%S)
tar --zstd -cf "llmage-data-$STAMP.tar.zst" db saved gamelogs Mage.Server/config/config.xml
```

Adjust the last path to whichever configuration file the service actually loads.

Stop the server first, or accept that a database copied mid-write may not restore cleanly. If a
stop is not possible, copy `db/` on its own with the server idle and copy `saved/` and `gamelogs/`
separately; those two are append-only between games and tolerate a live copy far better than the
databases do.

Restore by stopping the server, unpacking over the same relative paths, and starting it again.
Relative paths matter: the data directories are resolved against the process working directory.

## Replay retention

`saved/` grows without limit. Nothing in the server rotates it, and a long game produces a save of
several megabytes. `scripts/replay-retention.sh` prunes by size, by age, or both.

```bash
scripts/replay-retention.sh saved --max-bytes 20G --keep 50            # dry run
scripts/replay-retention.sh saved --max-bytes 20G --keep 50 --apply    # delete
scripts/replay-retention.sh saved --max-age-days 30 --apply
```

| Flag | Effect |
|---|---|
| `--max-bytes SIZE` | Cap the total size of replay pairs, deleting oldest first. Accepts plain bytes or `K`, `M`, `G`, `T` suffixes. |
| `--max-age-days N` | Delete replays older than `N` days. |
| `--keep N` | Never delete the `N` most recent replays. Applies to both policies. |
| `--apply` | Delete. Without it the script only reports. |

The default is a dry run that prints a decision table and a final `SUMMARY` line with counters for
checked, kept, and would-be-deleted replays. Re-running is safe: deletion is idempotent.

Safety rules are fixed, not configurable:

- A replay is a pair: `<uuid>.game` and `<uuid>.json`. Both are deleted together, sidecar first.
- Files modified in the last 120 seconds are never deleted, so a save that is being written at the
  moment the pruner runs is left alone.
- Only canonical UUID file names are considered. Anything else in the directory is reported and
  left untouched.
- Symlinks and directories are never deleted.

A nightly job is a reasonable shape:

```cron
15 4 * * * cd /opt/llmage && scripts/replay-retention.sh saved --max-bytes 20G --keep 50 --apply >> oldlogs/retention.log 2>&1
```

Run it off-hours where possible. A replay deleted between the moment a browser lists it and the
moment it opens fails that one open; refreshing the list recovers.

## Replay format migration

Replays are stored in a zstd container. Saves written by an older build used gzip, and the reader
does not fall back, so an old save fails to open with an explicit legacy-container message.
`scripts/replay-codec-migrate.sh` converts them once:

```bash
scripts/replay-codec-migrate.sh saved --dry-run    # list
scripts/replay-codec-migrate.sh saved              # convert
```

The conversion streams (`gzip -dc | zstd`), so memory use stays flat regardless of save size. It
verifies a byte-exact round trip before replacing the original, swaps through a temporary file and
a rename, skips files that are already zstd, and leaves anything with an unrecognised header
alone. Run it with the server stopped, or at least not saving the file being converted.

## Card database updates

The card database lives in `db/`. On startup the server compares the running build against the
build recorded in the database. When they differ, it drops and rebuilds the card table by scanning
card classes. That is normal after an upgrade, and it is why the first start after a deploy takes
longer.

Two failure modes are worth recognising:

| Symptom | Meaning | Fix |
|---|---|---|
| Server starts, loads zero cards, then restarts in a loop | The card database was rebuilt but the card classes were not found; usually an incomplete `lib/` | Re-run `deploy-release.sh`, then `bootstrap-plugins.sh`, and restart. |
| Card search or deck editing returns nothing while the game server works | The web proxy is reading an empty or missing database | Give the proxy a populated `db/` in its working directory and restart it. |

`clean_dbs.sh` at the repository root deletes every `cards.h2*.db` file under the tree. Use it when
a card database is corrupt beyond rebuilding; the server recreates it on the next start, at the cost
of a full scan.

> **LLMage addition.** The web proxy opens the card database at startup and refuses to bind its
> port when the database is empty, incomplete, or cannot resolve a known card. It also stamps the
> build rows so its own startup cannot trigger the rebuild that would leave it with an empty
> database. The practical rule: give the proxy a populated database and restart the server before
> the proxy after an upgrade.

## Disk usage

| Path | Typical growth | Reclaim |
|---|---|---|
| `saved/` | A few megabytes per saved game | `replay-retention.sh` |
| `gamelogs/` | Transcripts and post-game files per LLM game | `archive-logs.sh`, then prune `oldlogs/` |
| `oldlogs/` | One directory per restart | Delete old timestamped entries |
| `db/` | Slow, bounded by accounts and history | Never prune by hand |
| `mageserver.log` | Size- or date-rolled if configured | Rotation settings |

Watch the filesystem that holds `saved/`. A full disk mid-game corrupts the save being written and
takes the server down with it.

## Upgrade and rollback

Keep a copy of the working checkout before an upgrade. That copy is the rollback target, and it
costs nothing compared with rebuilding under pressure.

```bash
# 0. Keep the rollback target
cp -a . ../llmage.prev

# 1. Stop
sudo systemctl stop llmage-proxy llmage-server

# 2. Back up what cannot be rebuilt
STAMP=$(date +%Y%m%d_%H%M%S)
tar --zstd -cf "pre-upgrade-$STAMP.tar.zst" db saved Mage.Server/config/config.xml

# 3. Build and stage the new release
export MAVEN_OPTS=-Xmx9g
mvn clean install -DskipTests
(cd Mage.Server && mvn package assembly:single -DskipTests)
scripts/deploy-release.sh

# 4. Refresh plugin symlinks
scripts/bootstrap-plugins.sh

# 5. Start and verify
sudo systemctl start llmage-server
grep 'Started MAGE server' mageserver.log
sudo systemctl start llmage-proxy
curl -fsS http://127.0.0.1:8090/config
```

Verification is not optional: open the site, connect, and play one turn with an LLM seat before
declaring the upgrade done. A deploy that loads zero cards, or a hand-copied proxy of the wrong
flavour, looks healthy from outside until a client tries it.

To roll back, stop both services, move the upgraded tree aside, restore the previous copy, and
restore the configuration file if the upgrade changed it. The card database rebuilds itself when
the older build's card classes differ from the recorded build. Replays written by the newer build
may not open in the older one, so keep the backup from step 2 until the rollback is confirmed good.

## Related

- [Deployment](deployment.md)
- [Server configuration](server-configuration.md)
- [Security](security.md)
- [LLM providers](llm-providers.md)
- [Commands](../reference/commands.md)
- [Troubleshooting](../reference/troubleshooting.md)
