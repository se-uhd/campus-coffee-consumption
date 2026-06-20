package de.seuhd.campuscoffee.detekt

import org.jetbrains.kotlin.kdoc.parser.KDocKnownTag
import org.jetbrains.kotlin.kdoc.psi.api.KDoc
import org.jetbrains.kotlin.kdoc.psi.impl.KDocTag
import org.jetbrains.kotlin.psi.psiUtil.collectDescendantsOfType

/**
 * Collects the subject names documented with a `@param` or `@property` tag in this KDoc.
 *
 * @receiver the KDoc block to read
 * @return the names following the `@param`/`@property` tags
 */
internal fun KDoc.documentedParameterNames(): Set<String> =
    collectDescendantsOfType<KDocTag>()
        .filter { it.knownTag == KDocKnownTag.PARAM || it.knownTag == KDocKnownTag.PROPERTY }
        .mapNotNull { it.getSubjectName() }
        .toSet()
