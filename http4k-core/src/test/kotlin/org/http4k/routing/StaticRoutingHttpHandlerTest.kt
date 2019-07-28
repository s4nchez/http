package org.http4k.routing

import com.natpryce.hamkrest.absent
import com.natpryce.hamkrest.and
import com.natpryce.hamkrest.assertion.assertThat
import com.natpryce.hamkrest.equalTo
import com.natpryce.hamkrest.present
import org.http4k.core.ContentType.Companion.APPLICATION_XML
import org.http4k.core.ContentType.Companion.TEXT_HTML
import org.http4k.core.Filter
import org.http4k.core.Method.GET
import org.http4k.core.Request
import org.http4k.core.Response
import org.http4k.core.Status
import org.http4k.core.Status.Companion.INTERNAL_SERVER_ERROR
import org.http4k.core.Status.Companion.I_M_A_TEAPOT
import org.http4k.core.Status.Companion.NOT_FOUND
import org.http4k.core.Status.Companion.OK
import org.http4k.core.Uri.Companion.of
import org.http4k.core.then
import org.http4k.hamkrest.hasBody
import org.http4k.hamkrest.hasHeader
import org.http4k.hamkrest.hasStatus
import org.http4k.routing.ResourceLoader.Companion.Classpath
import org.junit.jupiter.api.Test
import java.util.concurrent.atomic.AtomicInteger

open class StaticRoutingHttpHandlerTest : RoutingHttpHandlerContract() {
    override val handler: RoutingHttpHandler = StaticRoutingHttpHandler(
        pathSegments = validPath,
        resourceLoader = ResourceLoader.Classpath(),
        extraFileExtensionToContentTypes = emptyMap()
    )

    private val pkg = javaClass.`package`.name.replace('.', '/')

    @Test
    fun `looks up contents of existing root file`() {
        val handler = "/svc" bind static()
        val result = handler(Request(GET, of("/svc/mybob.xml")))

        assertThat(result, hasBody("<xml>content</xml>") and hasHeader("Content-type", APPLICATION_XML.value))
    }

    @Test
    fun `does not serve contents of existing root file outside the scope`() {
        val handler = "/svc" bind static()
        val result = handler(Request(GET, of("/mybob.xml")))
        assertThat(result, hasStatus(NOT_FOUND))
    }

    @Test
    fun `can register custom mime types`() {
        val handler = "/svc" bind static(Classpath(), "myxml" to APPLICATION_XML)
        val result = handler(Request(GET, of("/svc/mybob.myxml")))
        assertThat(result, hasStatus(OK) and hasBody("<myxml>content</myxml>") and hasHeader("Content-type", APPLICATION_XML.value))
    }

    @Test
    fun `defaults to index html if is no route`() {
        val handler = "/svc" bind static()
        val result = handler(Request(GET, of("/svc")))
        assertThat(result, hasStatus(OK) and hasBody("hello from the root index.html") and hasHeader("Content-type", TEXT_HTML.value))
    }

    @Test
    fun `defaults to index html if is no route - root-context`() {
        val handler = "/" bind static()
        val result = handler(Request(GET, of("/")))
        assertThat(result, hasStatus(OK) and hasBody("hello from the root index.html") and hasHeader("Content-type", TEXT_HTML.value))
    }

    @Test
    fun `defaults to index html if is no route - non-root-context`() {
        val handler = "/svc" bind static(Classpath("org"))
        val result = handler(Request(GET, of("/svc")))
        assertThat(result, hasStatus(OK) and hasBody("hello from the io index.html") and hasHeader("Content-type", TEXT_HTML.value))
    }

    @Test
    fun `can apply filters`() {
        val rewritePathToRootIndex = Filter { next ->
            {
                next(it.uri(it.uri.path("/index.html")))
            }
        }
        val handler = rewritePathToRootIndex.then("/" bind static(Classpath("")))
        val result = handler(Request(GET, of("/asdas")))
        assertThat(result, hasStatus(OK) and hasBody("hello from the root index.html") and hasHeader("Content-Type", TEXT_HTML.value))
    }

    @Test
    fun `non existing index html if is no route`() {
        val handler = "/svc" bind static(Classpath("org/http4k"))
        val result = handler(Request(GET, of("/svc")))
        assertThat(result, hasStatus(NOT_FOUND))
    }

    @Test
    fun `looks up contents of existing subdir file - non-root context`() {
        val handler = "/svc" bind static()
        val result = handler(Request(GET, of("/svc/$pkg/StaticRouter.js")))
        assertThat(result, hasStatus(OK) and hasBody("function hearMeNow() { }") and hasHeader("Content-type", "application/javascript"))
    }

    @Test
    fun `looks up contents of existing subdir file`() {
        val handler = "/" bind static()
        val result = handler(Request(GET, of("/$pkg/StaticRouter.js")))
        assertThat(result, hasStatus(OK) and hasBody("function hearMeNow() { }") and hasHeader("Content-type", "application/javascript"))
    }

    @Test
    fun `can alter the root path`() {
        val handler = "/svc" bind static(Classpath(pkg))
        val result = handler(Request(GET, of("/svc/StaticRouter.js")))
        assertThat(result, hasStatus(OK) and hasBody("function hearMeNow() { }") and hasHeader("Content-type", "application/javascript"))
    }

    @Test
    fun `looks up non existent-file`() {
        val handler = "/svc" bind static()
        val result = handler(Request(GET, of("/svc/NotHere.xml")))
        assertThat(result, hasStatus(NOT_FOUND))
    }

    @Test
    fun `cannot serve a directory`() {
        val handler = "/svc" bind static()
        val result = handler(Request(GET, of("/svc/org")))
        assertThat(result, hasStatus(NOT_FOUND))
    }

    @Test
    fun `looks up non existent path`() {
        val handler = "/svc" bind static()
        val result = handler(Request(GET, of("/bob/StaticRouter.js")))
        assertThat(result, hasStatus(NOT_FOUND))
    }

    @Test
    fun `can't subvert the path`() {
        val handler = "/svc" bind static()
        assertThat(handler(Request(GET, of("/svc/../svc/Bob.xml"))), hasStatus(NOT_FOUND))
        assertThat(handler(Request(GET, of("/svc/~/.bashrc"))), hasStatus(NOT_FOUND))
    }

    @Test
    fun `as a router when does not fine file`() {
        val handler = "/svc" bind static()

        assertThat(handler.match(Request(GET, of("/svc/../svc/Bob.xml"))), absent())
    }

    @Test
    fun `as a router finds file`() {
        val handler = "/svc" bind static()
        val req = Request(GET, of("/svc/mybob.xml"))
        assertThat(handler.match(req)?.invoke(req), present(hasStatus(OK)))
    }

    @Test
    fun `can add filter to router`() {
        val changePathFilter = Filter { next ->
            { next(it.uri(it.uri.path("/svc/mybob.xml"))) }
        }
        val handler = "/svc" bind changePathFilter.then(static())
        val req = Request(GET, of("/svc/notmybob.xml"))
        assertThat(handler(req), hasStatus(OK))
    }

    @Test
    fun `can add filter to a RoutingHttpHandler`() {
        val calls = AtomicInteger(0)
        val changePathFilter = Filter { next ->
            {
                calls.incrementAndGet()
                next(it.uri(it.uri.path("/svc/mybob.xml")))
            }
        }
        val handler = changePathFilter.then("/svc" bind static())
        val req = Request(GET, of("/svc/notmybob.xml"))
        assertThat(handler(req), hasStatus(OK))
        assertThat(calls.get(), equalTo(1))
    }

    @Test
    fun `application of filter - nested and first`() {
        val handler = routes("/first" bind static(), "/second" bind GET to { Response(INTERNAL_SERVER_ERROR) })

        handler.assertFilterCalledOnce("/first/mybob.xml", OK)
        handler.assertFilterCalledOnce("/first/notmybob.xml", NOT_FOUND)
        handler.assertFilterCalledOnce("/second", INTERNAL_SERVER_ERROR)
        handler.assertFilterCalledOnce("/third", NOT_FOUND)
    }

    @Test
    fun `application of filter - nested and middle`() {
        val handler = routes(
            "/first" bind GET to { Response(INTERNAL_SERVER_ERROR) },
            "/second" bind static(),
            "/third" bind GET to { Response(I_M_A_TEAPOT) }
        )

        handler.assertFilterCalledOnce("/first", INTERNAL_SERVER_ERROR)
        handler.assertFilterCalledOnce("/second/mybob.xml", OK)
        handler.assertFilterCalledOnce("/second/notmybob.xml", NOT_FOUND)
        handler.assertFilterCalledOnce("/third", I_M_A_TEAPOT)
        handler.assertFilterCalledOnce("/fourth", NOT_FOUND)
    }

    @Test
    fun `application of filter - nested and last`() {
        val handler = routes(
            "/first" bind GET to { Response(INTERNAL_SERVER_ERROR) },
            "/second" bind GET to { Response(I_M_A_TEAPOT) },
            "/third" bind static()
        )
        handler.assertFilterCalledOnce("/first", INTERNAL_SERVER_ERROR)
        handler.assertFilterCalledOnce("/second", I_M_A_TEAPOT)
        handler.assertFilterCalledOnce("/third/mybob.xml", OK)
        handler.assertFilterCalledOnce("/fourth", NOT_FOUND)
    }

    @Test
    fun `application of filter - unnested`() {
        val handler = "/first" bind static()
        handler.assertFilterCalledOnce("/first/mybob.xml", OK)
        handler.assertFilterCalledOnce("/first/notmybob.xml", NOT_FOUND)
        handler.assertFilterCalledOnce("/second", NOT_FOUND)
    }

    @Test
    fun `application of filter - raw`() {
        val handler = static()
        handler.assertFilterCalledOnce("/mybob.xml", OK)
        handler.assertFilterCalledOnce("/notmybob.xml", NOT_FOUND)
        handler.assertFilterCalledOnce("/foo/bob.xml", NOT_FOUND)
    }

    private fun RoutingHttpHandler.assertFilterCalledOnce(path: String, expected: Status) {
        val calls = AtomicInteger(0)
        val handler = Filter { next -> { calls.incrementAndGet(); next(it) } }.then(this)
        assertThat(handler(Request(GET, of(path))), hasStatus(expected))
        assertThat(calls.get(), equalTo(1))
    }
}
