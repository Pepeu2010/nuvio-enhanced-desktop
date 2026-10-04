package com.nuvio.app.core.diagnostics

import io.sentry.SentryEvent
import io.sentry.Breadcrumb
import io.sentry.protocol.SentryException
import io.sentry.protocol.Message
import io.sentry.protocol.Request
import io.sentry.protocol.User
import kotlin.test.Test
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertEquals

class SentryEventSanitizerTest {
    @Test
    fun removesPersonalAndRequestData() {
        val event = SentryEvent().apply {
            request = Request().apply { url = "https://example.com/private" }
            user = User().apply { email = "person@example.com" }
            serverName = "private-computer"
        }

        assertSame(event, SentryEventSanitizer.sanitize(event))
        assertNull(event.request)
        assertNull(event.user)
        assertNull(event.serverName)
    }

    @Test
    fun dropsIgnoredIssueMessages() {
        val event = SentryEvent().apply {
            message = Message().apply { formatted = "File IO on Main Thread" }
        }

        assertNull(SentryEventSanitizer.sanitize(event))
    }

    @Test
    fun removesCredentialsFromMessagesExceptionsAndBreadcrumbs() {
        val event = SentryEvent().apply {
            message = Message().apply { formatted = "GET https://private.test/token/stream failed" }
            exceptions = listOf(SentryException().apply { value = "Bearer secret-value" })
            addBreadcrumb(Breadcrumb().apply {
                message = "https://private.test/secret"
                setData("url", "https://private.test/query?api_key=secret")
            })
        }
        SentryEventSanitizer.sanitize(event)
        assertEquals("GET [redacted-url] failed", event.message?.formatted)
        assertEquals("Bearer [redacted]", event.exceptions?.single()?.value)
        assertEquals("[redacted-url]", event.breadcrumbs?.single()?.message)
        assertEquals(0, event.breadcrumbs?.single()?.data?.size)
    }
}
