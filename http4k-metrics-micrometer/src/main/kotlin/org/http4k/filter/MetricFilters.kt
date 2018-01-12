package org.http4k.filter

import io.micrometer.core.instrument.Counter
import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.Tags
import io.micrometer.core.instrument.Timer
import org.http4k.core.Filter
import org.http4k.core.HttpHandler
import org.http4k.core.Request
import org.http4k.core.Response
import org.http4k.routing.RoutingHttpHandler
import java.time.Duration

object MetricFilters {
    object Server {
        operator fun invoke(meterRegistry: MeterRegistry, config: Config = Config()): Filter {

            val clock = meterRegistry.config().clock()

            return Filter { next ->
                { request ->
                    val startTime = clock.monotonicTime()
                    val response = next(request)
                    response.let {
                        val duration = Duration.ofNanos(clock.monotonicTime() - startTime)

                        val tags = Tags.zip(config.methodName, request.method.name, config.statusName, it.status.code.toString())

                        Counter.builder(config.httpServerRequestsCounter.name)
                                .description(config.httpServerRequestsCounter.description)
                                .tags(tags)
                                .register(meterRegistry)
                                .increment()

                        HandlerMetrics.extractFrom(it)?.let { (type, path) ->
                            val formattedPath = config.pathFormatter(path)
                            when (type) {
                                HandlerMeter.Timer -> Timer.builder(config.httpServerHandlersTimer.name)
                                        .description(config.httpServerHandlersTimer.description)
                                        .tags(Tags.concat(tags, config.pathName, formattedPath))
                                        .register(meterRegistry)
                                        .record(duration)
                                HandlerMeter.Counter -> Counter.builder(config.httpServerHandlersCounter.name)
                                        .description(config.httpServerHandlersCounter.description)
                                        .tags(Tags.concat(tags, config.pathName, formattedPath))
                                        .register(meterRegistry)
                                        .increment()
                            }
                        }

                        HandlerMetrics.removeMetricHeadersFrom(response)
                    }
                }
            }
        }

        data class Config(
                val httpServerRequestsCounter: MeterName = MeterName("http.server.requests", "Total number of server requests"),
                val httpServerHandlersTimer: MeterName = MeterName("http.server.handlers", "Timings of server handlers"),
                val httpServerHandlersCounter: MeterName = MeterName("http.server.handlers", "Total number of handler requests"),
                val methodName: String = "method",
                val statusName: String = "status",
                val pathName: String = "path",
                val pathFormatter: (String) -> String = defaultPathFormatter
        ) {
            companion object {
                private val notAlphaNumUnderscore: Regex = "[^a-zA-Z0-9_]".toRegex()
                val defaultPathFormatter: (String) -> String = {
                    it.replace('/', '_').replace(notAlphaNumUnderscore, "")
                }
            }
        }

    }

    data class MeterName(val name: String, val description: String?)
}

val timer = HandlerMeter.Timer
val counter = HandlerMeter.Counter

enum class HandlerMeter {
    Timer,
    Counter
}

infix fun RoutingHttpHandler.with(type: HandlerMeter) = withFilter(when (type) {
    HandlerMeter.Timer -> HandlerMetrics.Timer
    HandlerMeter.Counter -> HandlerMetrics.Counter
})

private object HandlerMetrics {
    private val uriTemplateHeader = "x-http4k-metrics-uri-template"
    private val meterTypeHeader = "x-http4k-metrics-type"

    fun extractFrom(response: Response): Pair<HandlerMeter, String>? {
        val type = response.header(meterTypeHeader)?.let { HandlerMeter.valueOf(it) }
        val path = response.header(uriTemplateHeader)
        return if (type != null && path != null) type to path else null
    }

    fun removeMetricHeadersFrom(response: Response): Response =
            response.removeHeader(uriTemplateHeader).removeHeader(meterTypeHeader)

    private fun Response.copyUriTemplateHeader(request: Request, type: HandlerMeter) =
            replaceHeader(uriTemplateHeader, request.header("x-uri-template")).
                    replaceHeader(meterTypeHeader, header(meterTypeHeader) ?: type.name)

    object Timer : Filter {
        override fun invoke(next: HttpHandler): HttpHandler = {
            request -> next(request).copyUriTemplateHeader(request, HandlerMeter.Timer)
        }
    }

    object Counter : Filter {
        override fun invoke(next: HttpHandler): HttpHandler = {
            request -> next(request).copyUriTemplateHeader(request, HandlerMeter.Counter)
        }
    }
}