package de.seuhd.campuscoffee.api.support

import de.seuhd.campuscoffee.domain.model.GlobalActivityEntry
import org.apache.commons.csv.CSVFormat
import org.springframework.http.ContentDisposition
import org.springframework.http.HttpHeaders
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.stereotype.Component
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody
import java.io.OutputStreamWriter
import java.nio.charset.StandardCharsets
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

/**
 * Renders the admin global activity feed as a downloadable CSV (`text/csv`). It writes a UTF-8 byte-order mark
 * first so a spreadsheet (notably Excel) detects the encoding and renders user names with umlauts correctly,
 * then a header row and one row per [GlobalActivityEntry], using Apache Commons CSV for RFC 4180
 * quoting/escaping. Timestamps are ISO-8601 in UTC and money stays as raw integer euro cents (the UI formats
 * euros; the export stays machine-friendly).
 */
@Component
class ActivityCsvResponder {
    /**
     * Builds the streaming CSV response for the given activity [entries] (already newest-first and bounded by
     * the export cap upstream), downloaded as `activity.csv`. The list is assembled by the caller (the running
     * balances require a full walk); this responder then writes it to the response one row at a time as the CSV
     * is produced, without building a second full copy of the rendered output.
     *
     * @param entries the global activity entries to export
     * @return the CSV with the `text/csv; charset=UTF-8` content type and an `activity.csv` download filename
     */
    fun csvResponse(entries: List<GlobalActivityEntry>): ResponseEntity<StreamingResponseBody> {
        val body =
            StreamingResponseBody { out ->
                out.write(UTF8_BOM)
                val writer = OutputStreamWriter(out, StandardCharsets.UTF_8)
                CSVFormat.DEFAULT
                    .builder()
                    .setHeader(*HEADERS)
                    .build()
                    .print(writer)
                    // closing the printer flushes the writer and the underlying response stream
                    .use { csv -> entries.forEach { csv.printRecord(row(it)) } }
            }
        return ResponseEntity
            .ok()
            .contentType(CSV_MEDIA_TYPE)
            .header(HttpHeaders.CONTENT_DISPOSITION, attachment("activity.csv"))
            .body(body)
    }

    /** The CSV cell values for one entry, in [HEADERS] order; a null cell renders as an empty field. */
    private fun row(entry: GlobalActivityEntry): List<Any?> =
        listOf(
            // timestamp/type are machine-generated (ISO-8601, an enum name), so they need no formula guard
            entry.createdAt.atOffset(ZoneOffset.UTC).format(DateTimeFormatter.ISO_OFFSET_DATE_TIME),
            entry.type.name,
            // the free-text cells are user-controlled (a user edits their own name and writes notes), so guard
            // them against spreadsheet formula injection; the numeric cells below are written as numbers, never
            // as formulas
            formulaSafe(entry.subjectLogin),
            formulaSafe(entry.subjectName),
            formulaSafe(entry.actorLogin),
            entry.userEffectCents,
            entry.userBalanceCents,
            entry.kittyEffectCents,
            entry.kittyBalanceCents,
            entry.count,
            entry.delta,
            entry.weightGrams,
            entry.privateAmountCents,
            entry.kittyAmountCents,
            entry.priceAmountCents,
            formulaSafe(entry.note)
        )

    /**
     * Neutralizes spreadsheet formula injection in a free-text cell: a value whose first character a
     * spreadsheet would treat as the start of a formula (`=`, `+`, `-`, `@`, a tab, or a carriage return) is
     * prefixed with a single quote so Excel/LibreOffice render it as literal text rather than evaluating it
     * (which a `=HYPERLINK(...)`/`=WEBSERVICE(...)` cell would use to exfiltrate, or a DDE cell to run a
     * command). RFC 4180 quoting alone does not prevent this. A null or empty value is returned unchanged.
     *
     * @param value the user-controlled cell value to guard
     */
    private fun formulaSafe(value: String?): String? =
        if (!value.isNullOrEmpty() && value.first() in FORMULA_TRIGGERS) "'$value" else value

    /** A `Content-Disposition: attachment` header value carrying the given download [filename]. */
    private fun attachment(filename: String): String =
        ContentDisposition
            .attachment()
            .filename(filename)
            .build()
            .toString()

    private companion object {
        /** The leading characters a spreadsheet treats as the start of a formula in an unguarded text cell. */
        private val FORMULA_TRIGGERS = setOf('=', '+', '-', '@', '\t', '\r')

        /** The UTF-8 byte-order mark, so a spreadsheet opens the export as UTF-8 and umlaut names stay intact. */
        private val UTF8_BOM = byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte())

        /** The `text/csv; charset=UTF-8` media type the export is served with. */
        private val CSV_MEDIA_TYPE = MediaType.parseMediaType("text/csv; charset=UTF-8")

        /** The CSV column headers, in row order. */
        private val HEADERS =
            arrayOf(
                "timestamp",
                "type",
                "subjectLogin",
                "subjectName",
                "actor",
                "userEffectCents",
                "userBalanceCents",
                "kittyEffectCents",
                "kittyBalanceCents",
                "count",
                "delta",
                "weightGrams",
                "privateAmountCents",
                "kittyAmountCents",
                "priceAmountCents",
                "note"
            )
    }
}
