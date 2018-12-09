package org.http4k.stream

import org.http4k.client.ApacheClient
import org.http4k.core.BodyMode
import org.http4k.core.HttpHandler
import org.http4k.server.KtorCIO
import org.http4k.server.ServerConfig
import org.http4k.streaming.StreamingContract
import java.util.Random

class KtorCIOStreamingTest : StreamingContract() {
    override fun serverConfig(port: Int): ServerConfig = KtorCIO(Random().nextInt(1000) + 8000)

    override fun createClient(): HttpHandler =
            ApacheClient(requestBodyMode = BodyMode.Stream, responseBodyMode = BodyMode.Stream)
}