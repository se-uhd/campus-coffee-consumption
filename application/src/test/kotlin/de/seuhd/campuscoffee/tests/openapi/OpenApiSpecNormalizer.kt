package de.seuhd.campuscoffee.tests.openapi

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.node.ObjectNode

/**
 * Canonicalizes an OpenAPI JSON document so the committed spec and the live one can be compared for
 * contract drift without false positives from fields that legitimately vary between runs and releases.
 *
 * Two fields are neutralized rather than left as-is: `info.version` is pinned to a fixed placeholder (the
 * live value is the build version, which would otherwise churn the committed file on every release, and the
 * models-only frontend codegen ignores it), and the top-level `servers` block is dropped (its URL is the
 * test's random port). `info.version` is kept (set to the placeholder) rather than removed because it is a
 * required OpenAPI field and the frontend code generator validates the spec. Everything else (the
 * description, paths, components, tags) is the contract and is compared verbatim. The document's existing
 * key order is preserved (springdoc emits it deterministically); it is only pretty-printed, so the
 * generated DTO fields keep their declaration order and the committed spec is a readable, diff-friendly
 * artifact.
 */
object OpenApiSpecNormalizer {
    private const val PLACEHOLDER_VERSION = "0.0.0"

    private val mapper: ObjectMapper = ObjectMapper()

    /**
     * Returns the canonical form of [json]: `info.version` pinned to a placeholder, `servers` removed,
     * pretty-printed in the document's own key order, with a trailing newline.
     *
     * @param json the raw OpenAPI document (the live `/api/api-docs` body or the committed file)
     * @return the normalized, deterministic JSON string
     */
    fun canonical(json: String): String {
        val root = mapper.readTree(json)
        require(root is ObjectNode) { "OpenAPI spec must be a JSON object, was ${root.nodeType}." }
        (root.get("info") as? ObjectNode)?.put("version", PLACEHOLDER_VERSION)
        root.remove("servers")
        return mapper.writerWithDefaultPrettyPrinter().writeValueAsString(root) + "\n"
    }
}
