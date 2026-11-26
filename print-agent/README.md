# Print agent

A small Spring Boot app on the shop PC (phase 03). The till sends it receipts; it prints them on the local receipt
printer, keeps every request on disk until it is printed, and takes reprints from the cloud while the cloud is
reachable. Decisions: [ADR-031](../docs/decisions/ADR-031-print-agent.md).

## Local API (127.0.0.1:9123 only)

Every call sends the shared token chosen at install: `Authorization: Bearer <agent.local-token>`; anything else is
answered 401. Requests: [`http/print-agent.http`](../http/print-agent.http).

| Call | What it does |
| --- | --- |
| `POST /v1/print` | A receipt in the documents.yaml `ReceiptModel` shape. Rendered at once with the cached template and kept on disk; **200** `PRINTED` when it came out, **202** `QUEUED` when it waits for the printer; **400** `INVALID_RECEIPT` when it cannot be read. |
| `POST /v1/drawer/open` | Opens the cash drawer now (**204**), or **503** `PRINTER_OFFLINE` when the printer is away: a drawer never opens later by surprise. |
| `GET /v1/status` | The printer (`ONLINE`, `OFFLINE`, `PAPER_OUT`, `ERROR`), the requests waiting, the cloud link (`DISABLED`, `CONNECTING`, `ONLINE`, `OFFLINE`), the last contact and the cached template version. |

Browser tills on another origin are let in by `agent.allowed-origins` (CORS).

## Printers

`agent.printer.type` in `agent.yaml`:

- `TCP`: network printers on raw TCP (`host`, `port` 9100). The paper sensor is read with `DLE EOT 4`.
- `QUEUE`: a USB printer behind an operating system print queue (`queue-name`), fed raw bytes through `javax.print`.
- `SERIAL`: a serial or USB-serial port (`serial-port`, `baud-rate`) through jSerialComm.

## Queue and cloud link

- Requests are kept in an H2 file in `agent.data-dir` (default `~/bmp-print-agent`) and printed oldest first. One
  that fails waits 1 s, then twice as long each time, at most `agent.queue.max-backoff`, and holds the ones behind
  it, so receipts come out in order. The till's requests are kept until printed; a reprint from the cloud is
  reported `FAILED` after `agent.queue.cloud-max-attempts`. Printed requests are removed after 7 days.
- With `agent.cloud.enabled`, the agent gets an access token with the device credential (client credentials), keeps
  the receipt template (`GET /api/v1/print-agents/{id}/template`) to print offline, long-polls
  `GET /api/v1/print-agents/{id}/jobs:next` and reports `POST /api/v1/print-jobs/{id}/result` with the printer's
  status. A job handed out again prints once; its result is sent until the cloud has it, with the same idempotency
  key.

## Install and run

```bash
./gradlew :print-agent:bootRun                                 # development, with ./agent.yaml
./gradlew :print-agent:jpackage -PinstallerType=exe            # Windows installer (WiX Toolset on the PATH)
./gradlew :print-agent:jpackage -PinstallerType=deb            # Debian/Ubuntu package (fakeroot)
```

Copy [`agent.example.yaml`](agent.example.yaml) to `~/bmp-print-agent/agent.yaml` and fill it in; it holds the local
token and the device secret, so it is never committed. Logs are ECS JSON lines in
`~/bmp-print-agent/logs/print-agent.log` (10 MB files, 14 kept); receipts are never logged.
