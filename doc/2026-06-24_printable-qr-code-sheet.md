# A printable PDF sheet of every member's QR code

This record describes a small admin feature: a one-click download of every active member's capability QR
code as a single print-ready PDF, laid out as a grid with each member's login name beneath their code. It
also records a related change that narrows both bulk QR downloads to active members only. The motivation is
practical: the existing ZIP download gives one PNG per member, which is the wrong shape for the common need,
printing a single sheet to pin on the kitchen wall. A PDF grid is that sheet.

## Context

An admin could already download a single member's QR code (`GET /api/users/{id}/qr.png`) and a ZIP archive
of every member's code (`GET /api/users/qr.zip`, one `<loginName>.png` per entry). The ZIP is useful for
embedding codes one at a time, but to put codes on the wall you want them already arranged on a page. This
feature adds that page, and reuses the existing QR generation and capability-URL machinery unchanged.

## What it does

- A new admin-only endpoint, `GET /api/users/qr.pdf`, returns `application/pdf` as `coffee-qr-codes.pdf`.
- The PDF is an A4 portrait grid, three columns, each cell a QR code with the member's login name centered
  on the line below it. The cells flow across the columns and onto further pages as the membership grows.
- The members page (`/admin/users`) gains a "PDF" button beside the existing "ZIP" button. Both fetch the
  file as a blob through `HttpClient` (so the JWT interceptor attaches the bearer token, which an `<img>` or
  a plain link could not) and trigger a browser download.

## Architecture: a PDF port with a data-layer adapter

The QR encoder (ZXing) sits behind the `QrCodeGenerator` domain port precisely so the encoding library
never reaches the web or domain layers. A PDF library is the same kind of thing, a concrete rendering
dependency, so it gets the same treatment:

- The domain owns the abstraction: a `QrGridPdfGenerator` port and a `LabeledQrCode` carrier (a label plus
  the pre-rendered QR PNG bytes).
- The data layer owns the implementation: `PdfBoxQrGridGenerator`, backed by Apache PDFBox. It receives
  already-rendered images and only arranges them, so it carries no QR-encoding and no business logic.
- The api-layer `CapabilityQrResponder` orchestrates. It renders each member's QR once through the existing
  `QrCodeGenerator` and hands the PDF adapter the labelled images. The ZIP response already worked this way;
  the PDF response shares its member selection.

Why the PDFBox code is infrastructure and not domain logic: hexagonal architecture forbids the domain from
depending on a concrete technology, and `PdfBoxQrGridGenerator` is defined entirely by one, PDFBox. It holds
no rule anyone would argue about (login names and image bytes in, PDF bytes out) and would be discarded
wholesale if the renderer changed. The business-meaningful decisions stay outside the adapter: the
active-only selection, the 1000-member cap, and the choice of the login name as the label all live in the
responder and the domain. ArchUnit keeps the PDF library out of the api and domain modules, exactly as it
does for ZXing.

## PDF layout

- A4 portrait, three columns. The number of rows per page is derived from the page height and the cell size,
  which currently yields twelve codes per page.
- The grid renders the QR at 512 px (a dedicated `GRID_QR_PX`), smaller than the 1024 px standalone PNG. A
  grid cell is about 5.7 cm wide, so 512 px prints at well over 200 dpi, and the smaller image keeps the
  document size reasonable across a whole membership.
- The QR PNGs carry a transparent background, so each draws cleanly over the white page with no opaque box.
- Labels use the standard Helvetica font. A login name wider than its cell is truncated with an ellipsis,
  and any character the standard font cannot encode is replaced (login names are ASCII in practice, so this
  is a defensive fallback). An empty selection produces a valid one-page sheet noting there are no members,
  rather than an error.

## Scope change: the bulk downloads are active-only

Both `GET /api/users/qr.pdf` and the existing `GET /api/users/qr.zip` now include only active members. A
deactivated member's wall code is retired, so it belongs on neither a reprint sheet nor the bulk archive.
This is a behavior change to the ZIP, which previously included every member with a token. The single-member
`GET /api/users/{id}/qr.png` is unchanged: an admin can still re-download a specific member's code
regardless of their active state. The eligible-member selection and the cap are computed once and shared by
both responses, so they can never drift apart.

## Library choice: Apache PDFBox

Apache PDFBox (3.0.x) draws images and text directly, which is all a QR grid needs. Its license is
Apache-2.0, compatible with this project's MIT license, as is its `fontbox` transitive. The alternatives
were rejected on licensing: iText 7 is AGPL or commercial, and OpenPDF is LGPL/MPL. A permissively licensed
dependency keeps the project's own licensing simple, so PDFBox was the clear choice.

## Testing

- A data-layer unit test (`PdfBoxQrGridGeneratorTest`) renders real QR PNGs from the ZXing adapter, then
  reopens the output with PDFBox to assert it is a valid PDF, that the page text contains every login-name
  label, that the grid paginates once the entries exceed one page, that an empty list yields a valid
  one-page document, and that an over-long label renders without failing.
- System tests cover the endpoint: an admin download returns 200 with `application/pdf`, the
  `coffee-qr-codes.pdf` filename, and the `%PDF` magic bytes; a member's capability token is refused with
  403; and a deactivated member is excluded from the ZIP (and the PDF still serves), proving the shared
  active-only selection.

See `CLAUDE.md` (the REST API and the ports-and-adapters sections) and the earlier records
`doc/2026-06-20_coffee-consumption-event-sourcing-and-capability-urls.md` for the capability-URL scheme that
the QR codes encode.
