package org.http4k.testing

import org.http4k.core.Body
import org.http4k.core.ContentType.Companion.TEXT_HTML
import org.http4k.core.Credentials
import org.http4k.core.HttpHandler
import org.http4k.core.Method.GET
import org.http4k.core.Method.POST
import org.http4k.core.Request
import org.http4k.core.Response
import org.http4k.core.Status.Companion.FORBIDDEN
import org.http4k.core.Status.Companion.OK
import org.http4k.core.Status.Companion.TEMPORARY_REDIRECT
import org.http4k.core.query
import org.http4k.core.then
import org.http4k.core.with
import org.http4k.filter.ServerFilters
import org.http4k.lens.FormField
import org.http4k.lens.Header.Common.LOCATION
import org.http4k.lens.Query
import org.http4k.lens.Validator.Feedback
import org.http4k.lens.string
import org.http4k.lens.uri
import org.http4k.lens.webForm
import org.http4k.routing.bind
import org.http4k.routing.routes
import org.http4k.security.OAuthClientConfig
import org.http4k.template.HandlebarsTemplates
import org.http4k.template.renderToResponse
import java.util.UUID
import java.util.UUID.randomUUID


/**
 *     https://accounts.google.com/o/oauth2/v2/auth?
client_id=424911365001.apps.googleusercontent.com&
response_type=code&
scope=openid%20email&
redirect_uri=http://localhost:9000/callback
state=below
encode!!    security_token=138r5719ru3e1&url=https://oauth2-login-demo.example.com/myHome&
nonce=random&
 */

class FakeOAuthServer(private val oAuthClientConfig: OAuthClientConfig) : HttpHandler {

    private val templates = HandlebarsTemplates().CachingClasspath()

    private val user = FormField.required("user")
    private val password = FormField.required("password")
    private val callbackUri = FormField.uri().required("callbackUri")
    private val loginForm = Body.webForm(Feedback, user, password, callbackUri).toLens()
    private val html = Body.string(TEXT_HTML).toLens()

    private val redirectUri = Query.uri().required("redirect_uri")
    private val users = mapOf("user" to "password")

    private val generatedCodes = mutableMapOf<UUID, Credentials>()

    private val login: HttpHandler = { templates.renderToResponse(OAuthLogin(oAuthClientConfig.serviceName, redirectUri(it))) }

    private val submit: HttpHandler = {
        val submitted = loginForm(it)
        val credentials = Credentials(user(submitted), password(submitted))
        when {
            submitted.errors.isNotEmpty() || users[credentials.user] != credentials.password ->
                Response(OK).with(html of templates(OAuthLogin("Google", callbackUri(submitted), "failed")))
            else -> randomUUID().let {
                generatedCodes[it] = credentials
                Response(TEMPORARY_REDIRECT).with(LOCATION of callbackUri(submitted).query("code", it.toString()))
            }
        }
    }

    private val generateAccessToken: (Request) -> Response = { Response(FORBIDDEN) }

    private val api = ServerFilters.CatchAll().then(
        routes(
            oAuthClientConfig.authPath bind POST to login,
            oAuthClientConfig.tokenPath bind POST to generateAccessToken,
            "/fakeLogin" bind POST to submit,
            "/" bind GET to { Response(OK).with(html of templates(OAuthIndex(oAuthClientConfig.serviceName))) }
        )
    )

    override fun invoke(p1: Request): Response = api(p1)
}
