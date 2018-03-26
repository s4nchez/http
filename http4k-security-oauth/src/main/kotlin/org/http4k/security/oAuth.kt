package org.http4k.security

import org.http4k.core.ContentType.Companion.APPLICATION_FORM_URLENCODED
import org.http4k.core.Credentials
import org.http4k.core.Filter
import org.http4k.core.HttpHandler
import org.http4k.core.Method.POST
import org.http4k.core.Request
import org.http4k.core.Response
import org.http4k.core.Status.Companion.FORBIDDEN
import org.http4k.core.Status.Companion.OK
import org.http4k.core.Status.Companion.TEMPORARY_REDIRECT
import org.http4k.core.Uri
import org.http4k.core.body.form
import org.http4k.core.cookie.Cookie
import org.http4k.core.cookie.cookie
import org.http4k.core.cookie.invalidateCookie
import org.http4k.core.query
import org.http4k.core.then
import org.http4k.core.toParameters
import org.http4k.core.toUrlFormEncoded
import org.http4k.core.with
import org.http4k.filter.ClientFilters
import org.http4k.lens.Header.Common.CONTENT_TYPE
import org.http4k.lens.Header.Common.LOCATION
import java.math.BigInteger
import java.security.SecureRandom
import java.time.Clock
import java.time.LocalDateTime
import java.time.ZoneId

typealias ModifyAuthRedirectUri = (Uri) -> Uri
typealias CsrfGenerator = () -> String

data class OAuthConfig(
    val serviceName: String,
    private val authBase: Uri,
    val authPath: String,
    val tokenPath: String,
    val credentials: Credentials,
    val apiBase: Uri = authBase) {
    val authUri = authBase.path(authPath)
}

class CookieBasedOAuth(oAuthConfig: OAuthConfig,
                       private val modifyAuthRedirect: ModifyAuthRedirectUri = { it },
                       private val clock: Clock) {

    private val csrfName = "${oAuthConfig.serviceName}Csrf"

    private val accessTokenName = "${oAuthConfig.serviceName}AccessToken"

    fun retrieveCsrf(p1: Request) = p1.cookie(csrfName)?.value

    fun redirectAuth(redirect: Response, csrf: String): Response {
        val expiry = LocalDateTime.ofInstant(clock.instant().plusSeconds(3600), ZoneId.of("GMT"))
        return redirect.cookie(Cookie(csrfName, csrf, expires = expiry))
    }

    fun isAuthed(request: Request): Boolean = request.cookie(accessTokenName) != null

    fun redirectToken(redirect: Response, accessToken: String): Response {
        val expires = LocalDateTime.ofInstant(clock.instant().plusSeconds(3600), ZoneId.of("GMT"))
        return redirect.cookie(Cookie(accessTokenName, accessToken, expires = expires)).invalidateCookie(csrfName)
    }

    fun modifyState(uri: Uri): Uri = modifyAuthRedirect(uri)

    fun failedResponse() = Response(FORBIDDEN).invalidateCookie(csrfName).invalidateCookie(accessTokenName)

}

internal class OAuthRedirectionFilter(
    private val clientConfig: OAuthConfig,
    private val callbackUri: Uri,
    private val scopes: List<String>,
    private val generateCrsf: CsrfGenerator = SECURE_GENERATE_RANDOM,
    private val cookieBasedOAuth: CookieBasedOAuth
) : Filter {

    override fun invoke(next: HttpHandler): HttpHandler = {
        if (cookieBasedOAuth.isAuthed(it)) next(it) else {
            val csrf = generateCrsf()
            val redirect = Response(TEMPORARY_REDIRECT).with(LOCATION of clientConfig.authUri
                .query("client_id", clientConfig.credentials.user)
                .query("response_type", "code")
                .query("scope", scopes.joinToString(" "))
                .query("redirect_uri", callbackUri.toString())
                .query("state", listOf("csrf" to csrf, "uri" to it.uri.toString()).toUrlFormEncoded())
                .with({ cookieBasedOAuth.modifyState(it) }))
            cookieBasedOAuth.redirectAuth(redirect, csrf)
        }
    }
}

internal class OAuthCallback(
    private val api: HttpHandler,
    private val clientConfig: OAuthConfig,
    private val callbackUri: Uri,
    private val cookieBasedOAuth: CookieBasedOAuth
) : HttpHandler {

    private fun codeToAccessToken(code: String) =
        api(Request(POST, clientConfig.tokenPath)
            .with(CONTENT_TYPE of APPLICATION_FORM_URLENCODED)
            .form("grant_type", "authorization_code")
            .form("redirect_uri", callbackUri.toString())
            .form("client_id", clientConfig.credentials.user)
            .form("client_secret", clientConfig.credentials.password)
            .form("code", code))
            .let {
                if (it.status == OK) it.bodyString() else null
            }

    override fun invoke(p1: Request): Response {
        val state = p1.query("state")?.toParameters() ?: emptyList()
        val crsfInState = state.find { it.first == "csrf" }?.second
        return p1.query("code")?.let { code ->
            val b = crsfInState != null && crsfInState == cookieBasedOAuth.retrieveCsrf(p1)
            if (b) {
                codeToAccessToken(code)?.let {
                    val originalUri = state.find { it.first == "uri" }?.second ?: "/"
                    cookieBasedOAuth.redirectToken(Response(TEMPORARY_REDIRECT).header("Location", originalUri), it)
                }
            } else null
        } ?: cookieBasedOAuth.failedResponse()
    }

}

class OAuth(client: HttpHandler,
            clientConfig: OAuthConfig,
            callbackUri: Uri,
            scopes: List<String>,
            generateCrsf: CsrfGenerator = SECURE_GENERATE_RANDOM,
            cookieBasedOAuth1: CookieBasedOAuth) {

    private val cookieBasedOAuth = cookieBasedOAuth1

    val api = ClientFilters.SetHostFrom(clientConfig.apiBase).then(client)

    val authFilter: Filter = OAuthRedirectionFilter(clientConfig, callbackUri, scopes, generateCrsf, cookieBasedOAuth)

    val callback: HttpHandler = OAuthCallback(api, clientConfig, callbackUri, cookieBasedOAuth)

    companion object
}

internal val SECURE_GENERATE_RANDOM = { BigInteger(130, SecureRandom()).toString(32) }