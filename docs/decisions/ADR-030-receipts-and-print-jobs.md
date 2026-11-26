# ADR-030: Receipts, print jobs and the choices DOC-01..DOC-06 leave open

- Status: accepted
- Date: 2026-11-26
- Phase / task: 03 / T03-09

## Context
DOC-01..DOC-06 fix what a receipt shows, the output formats, raster printing of Sinhala and Tamil, the drawer
kick, reprints through print agents and versioned templates. They leave open where the fonts come from, how a
template's free-form JSON maps onto a receipt, how a job is handed to an agent and what happens when its answer is
lost, and how an idempotent render replays bytes that are not text.

## Decisions
1. **Fonts are configurable; nothing is downloaded at build time.** `GlyphRaster.withFonts(paths)` takes TrueType
   files; `GlyphRaster.systemFonts()` (the default bean) picks the first installed font that can display the
   script (Windows: Nirmala UI; Debian/Ubuntu: `fonts-noto-core`), falling back to the JVM's sans-serif. DOC-03
   asks for Noto Sans Sinhala and Noto Sans Tamil to be bundled: they are SIL OFL 1.1 and may be, but fetching
   them is a download the user must approve. Until then the Sinhala test checks the raster's structure (`GS v 0`,
   48 or 72 bytes a row, more rows than a text line, ink) rather than a pixel snapshot, which would differ between
   machines with different fonts. Open question in `docs/PROGRESS.md`.
2. **Text mode for Latin, raster for every other script, per row.** A row whose text is all ASCII prints in the
   printer's own font; any other row is drawn at 24 px (36 px for the shop name) across the paper width (384 dots
   for 58 mm, 576 for 80 mm) and sent as one `GS v 0` image. The PDF does the same with images, so the three formats
   show the same rows.
3. **QR codes are the printer's own (`GS ( k`, model 2, error correction M).** No QR library is added; printers
   without the command skip it. The text preview shows `[QR <bill id>]`, the PDF the bill id.
4. **The render endpoint keeps its `Idempotency-Key`** (the contract lint requires it on every POST). Its ESC/POS
   and PDF answers are not text, so `libs/web-support` now stores a non-text body in Base64 and replays it byte for
   byte; JSON and text are stored as before.
5. **Template conventions** (the tenant's default receipt template, read through `TenantQueries`):
   `header.lines[0]` is the shop name and the rest the address; `footer.lines` the footer; `fields.phone`,
   `fields.showQr` (default false) and `fields.openDrawerOnCash` (default true) the switches; the tax number is the
   tax setting `taxRegistrationNumber`. Without a template the tenant's name heads an 80 mm receipt and the version
   is 0. The version is the tenant template's own (0-based, like every other version in the API).
6. **Print jobs are handed out at least once.** A job is QUEUED, then SENT when an agent's long poll takes it,
   then PRINTED or FAILED when the agent reports. A SENT job without an answer for one minute is handed out again
   (the agent lost it, or its answer was lost); the agent keeps the ids it printed so a second hand-out does not
   print twice. The first answer is final; the same answer again changes nothing, a different one is refused with
   `PRINT_JOB_STATE_INVALID`.
7. **Long polling waits on a monotonic timer**, at most 30 seconds, looking for a due job every 250 ms, each look in
   its own short transaction that also marks the agent as seen (`ONLINE`). The business clock is not used for the
   wait: it may stand still (tests) or jump. PostgreSQL `LISTEN/NOTIFY` would answer sooner; not needed at pilot
   volumes.
8. **Reprints go to the agent named, or to the first agent at the bill's location** (`NO_PRINT_AGENT` when there is
   none). A reprint needs only `sales:read`: whoever may see a bill may print its copy. The job carries the whole
   receipt (with the COPY banner), so the agent prints it without asking for anything else.
9. **Tills get the same template** through the sync dataset `receiptTemplate` (`DocumentsQueries.receiptTemplate`),
   so a till and its agent print alike while offline.

## Consequences
- Receipts in Sinhala or Tamil look right only where a suitable font is installed or configured, until the Noto
  fonts are bundled.
- A job may print twice if the agent loses its record of printed ids at the same moment its answer is lost; the
  copy says COPY, so no money is at stake.
- Reprints wait for the agent's next poll; an agent that is off does not block anything else.
