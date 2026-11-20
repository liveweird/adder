package ch.nokillswit.plugins

import io.ktor.server.application.*
import io.ktor.server.request.*
import io.ktor.server.response.*

// Strict Content-Security-Policy for the SPA + JSON API (production single-origin serving).
// - script-src 'self': the built SPA bundle is external/hashed; index.html has no inline app scripts.
// - style-src 'unsafe-inline': REQUIRED by Mantine (its style props/CSS variables render inline `style`
//   attributes and runtime <style> tags; Mantine 9 has no emotion) + the app's inline style={{}} usage.
//   Styles only — script-src stays strict, so this does not reopen script injection.
// - img-src 'self' data:: local assets + data URIs; remote images in user markdown won't load (acceptable).
// - connect-src 'self': the API is same-origin, and under CSP3 'self' also matches the same host's ws:/wss: (the live-update
//   socket); adding ws:/wss: scheme sources would allow sockets to ANY host, so the string stays as it is.
// Pinned verbatim by ServerTest — any edit must be a deliberate test edit too.
private const val APP_CSP =
    "default-src 'self'; " +
    "script-src 'self'; " +
    "style-src 'self' 'unsafe-inline'; " +
    "img-src 'self' data:; " +
    "font-src 'self'; " +
    "connect-src 'self'; " +
    "object-src 'none'; " +
    "base-uri 'self'; " +
    "form-action 'self'; " +
    "frame-ancestors 'none'"

// Defense-in-depth security response headers. The code is already XSS-safe (React escaping +
// react-markdown with no raw HTML); these headers contain the blast radius of any future
// regression and harden against clickjacking / MIME sniffing / referrer leakage.
fun Application.configureSecurityHeaders() {
    // The same condition as the route install in Routing.kt (exposesOpenApi()); the exemption must never apply where the
    // UI is not actually mounted.
    val swaggerMounted by lazy { exposesOpenApi() }
    intercept(ApplicationCallPipeline.Plugins) {
        // Swagger UI bootstraps with inline script/style, so it is exempt from the strict CSP - but ONLY
        // where it is actually mounted (exposeOpenApi on) and only for /openapi itself or its subtree.
        // Anywhere else `/openapi...` is an ordinary path (in production the SPA catch-all answers it with
        // index.html), and it must carry the full CSP.
        val path = call.request.path()
        val swaggerPath = path == "/openapi" || path.startsWith("/openapi/")
        call.appendSecurityHeaders(strictCsp = !(swaggerMounted && swaggerPath))
    }
}

/**
 * The security headers, once per call (a second call is a no-op). Normally the Plugins-phase interceptor above runs it; the early
 * answers `configureHttp` gives in the Setup phase (the 415 for an encoded body, the 400 for a malformed Host) end the pipeline BEFORE
 * that interceptor, so they call it themselves: no answer the app writes leaves without them.
 */
internal fun ApplicationCall.appendSecurityHeaders(strictCsp: Boolean = true) {
    val headers = response.headers
    if (headers["X-Content-Type-Options"] != null) return
    headers.append("X-Content-Type-Options", "nosniff")
    headers.append("X-Frame-Options", "DENY")
    headers.append("Referrer-Policy", "no-referrer")
    headers.append("Permissions-Policy", "geolocation=(), camera=(), microphone=(), payment=()")
    if (strictCsp) headers.append("Content-Security-Policy", APP_CSP)
}
