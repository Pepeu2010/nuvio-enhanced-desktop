package com.nuvio.app.core.diagnostics

import io.sentry.SentryEvent

internal object SentryEventSanitizer {
    val ignoredIssueText = listOf(
        "Large HTTP payload",
        "File IO on Main Thread",
    )

    fun sanitize(event: SentryEvent): SentryEvent? {
        event.request = null
        event.user = null
        event.serverName = null
        event.message?.let { message ->
            message.formatted = message.formatted?.let(::redactDiagnosticText)
            message.message = message.message?.let(::redactDiagnosticText)
            message.params = message.params?.map(::redactDiagnosticText)
        }
        event.exceptions?.forEach { exception ->
            exception.value = exception.value?.let(::redactDiagnosticText)
        }
        event.transaction = event.transaction?.let(::redactDiagnosticText)
        event.breadcrumbs?.forEach { breadcrumb ->
            breadcrumb.message = breadcrumb.message?.let(::redactDiagnosticText)
            breadcrumb.data.clear()
        }
        return if (shouldDrop(event)) null else event
    }

    private fun shouldDrop(event: SentryEvent): Boolean =
        eventText(event).any { text ->
            ignoredIssueText.any { ignored ->
                text.contains(ignored, ignoreCase = true)
            }
        }

    private fun eventText(event: SentryEvent): List<String> {
        val values = mutableListOf<String>()
        event.message?.formatted?.let(values::add)
        event.message?.message?.let(values::add)
        event.logger?.let(values::add)
        event.transaction?.let(values::add)
        event.exceptions?.forEach { exception ->
            exception.type?.let(values::add)
            exception.value?.let(values::add)
        }
        return values
    }
}
