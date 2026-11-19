package ch.nokillswit

import io.opentelemetry.api.common.AttributeKey
import io.opentelemetry.sdk.common.CompletableResultCode
import io.opentelemetry.sdk.trace.data.DelegatingSpanData
import io.opentelemetry.sdk.trace.data.EventData
import io.opentelemetry.sdk.trace.data.SpanData
import io.opentelemetry.sdk.trace.data.StatusData
import io.opentelemetry.sdk.trace.export.SpanExporter

private val EXCEPTION_MESSAGE: AttributeKey<String> = AttributeKey.stringKey("exception.message")
private val EXCEPTION_STACKTRACE: AttributeKey<String> = AttributeKey.stringKey("exception.stacktrace")

/**
 * Wraps every span exporter the SDK configures (`getOpenTelemetry`) so a recorded exception reaches the collector as its type only.
 * The Ktor instrumentation hands the RAW throwable of a failed request to `Span.recordException`, which writes `exception.message` and
 * `exception.stacktrace` (every "Caused by" message included); a database exception's message can carry bound values (a message body,
 * a search term), the very thing the 500 catch-all scrubs from the log (`plugins/ScrubbedFailure.kt`). Setting
 * `OTEL_TRACES_EXPORTER=otlp` must not route them to a collector, so the two attributes are dropped from every event and a status
 * description is emptied; `exception.type` and every other attribute stay.
 */
class ScrubbedSpanExporter(private val delegate: SpanExporter) : SpanExporter {
    override fun export(spans: Collection<SpanData>): CompletableResultCode = delegate.export(spans.map(::ScrubbedSpanData))

    override fun flush(): CompletableResultCode = delegate.flush()

    override fun shutdown(): CompletableResultCode = delegate.shutdown()

    override fun close() = delegate.close()
}

private class ScrubbedSpanData(private val span: SpanData) : DelegatingSpanData(span) {
    override fun getEvents(): List<EventData> = span.events.map { event ->
        val attributes = event.attributes
        if (attributes.get(EXCEPTION_MESSAGE) == null && attributes.get(EXCEPTION_STACKTRACE) == null) {
            event
        } else {
            val kept = attributes.toBuilder().remove(EXCEPTION_MESSAGE).remove(EXCEPTION_STACKTRACE).build()
            EventData.create(event.epochNanos, event.name, kept, event.totalAttributeCount)
        }
    }

    override fun getStatus(): StatusData =
        if (span.status.description.isEmpty()) span.status else StatusData.create(span.status.statusCode, "")
}
