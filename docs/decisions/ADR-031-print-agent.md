# ADR-031: Print agent design choices

- Status: accepted
- Date: 2026-11-26
- Phase / task: 03 / T03-10

## Context
The phase file fixes the agent's local API, its printer connections, the cloud link and that it never loses a
print request. It leaves open how requests are kept and ordered, when a drawer may open, how a reprint handed out
twice is printed once, how the local token is checked, and how installers are built.

## Decisions
1. **H2 in a file, with Flyway.** The queue and the cached template live in an embedded H2 database in
   `agent.data-dir`, migrated by Flyway like the platform's schemas, so an installed agent upgrades in place.
   SQLite would need a native driver; H2 is pure Java.
2. **Rendered on arrival, printed oldest first.** A receipt is rendered to ESC/POS when it arrives (with the
   template cached at that moment) and stored as bytes, so a bad receipt is refused at once (400) and what prints
   later is what the till asked for. The oldest request holds the ones behind it while it waits with back-off (1 s
   doubling, at most 60 s); a new request tries the oldest again at once, since a till printing is the moment the
   printer is most likely back.
3. **The till's requests are never given up; reprints are.** A request from the till stays until printed. A reprint
   from the cloud is reported `FAILED` after five attempts, so the cloud shows it and someone can print it again.
4. **The drawer opens now or not at all.** `POST /v1/drawer/open` is never queued: a drawer opening minutes later,
   when the printer comes back, would be a surprise at the counter. The till gets 503 and decides.
5. **A job is printed once by its id.** The queue keeps the cloud job id (unique). A job handed out again (its
   result was lost) is not printed again; its result is sent again with an idempotency key derived from the job id,
   so the cloud replays its answer. A job whose receipt cannot be read is reported `FAILED` at once.
6. **The local token is compared as SHA-256 digests** with `MessageDigest.isEqual`, so the comparison takes the same
   time whatever the token sent. The agent does not start without a token of at least 16 characters, and settings
   are printed without the token or the device secret. The web server binds `127.0.0.1` only.
7. **Receipt times stay local.** The till sends the receipt's local time; the agent reads and prints it as if it were
   UTC, so the receipt shows the till's time whatever the PC's time zone is.
8. **Installers with jpackage on the platform's own runner.** `./gradlew :print-agent:jpackage -PinstallerType=...`
   wraps the boot jar and a Java runtime; CI builds the Windows `.exe` (WiX Toolset from Chocolatey) and a Linux
   `.deb`. The agent runs as a per-user application with a menu entry; running it as a Windows service is left to
   the installer of the shop PC (open question).

## Consequences
- A long outage of the printer keeps receipts in order; nothing prints out of turn.
- A reprint may still print twice if the agent's data directory is lost at the moment its result is lost; the
  copy says COPY.
- Fonts for Sinhala and Tamil come from the PC, as for the platform (ADR-030 §1).
