package org.videolan.vlc.livetv

import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.IOException

/** VLC Redesign: why a Live TV source could not be loaded is a short reason, never the system's text (addresses) */
class TvErrorsTest {

    @Test
    fun networkFailuresByKind() {
        assertEquals(TvErrors.NO_ANSWER, TvErrors.reason(java.net.SocketTimeoutException("failed to connect to /192.0.2.5 (port 8080) from /192.0.2.23 (port 38654) after 20000ms")))
        assertEquals(TvErrors.NOT_FOUND, TvErrors.reason(java.net.UnknownHostException("Unable to resolve host \"tv.example\"")))
        assertEquals(TvErrors.UNREACHABLE, TvErrors.reason(java.net.ConnectException("failed to connect to /192.0.2.5 (port 8080)")))
        assertEquals(TvErrors.SECURE, TvErrors.reason(javax.net.ssl.SSLHandshakeException("Chain validation failed")))
    }

    @Test
    fun ownReasonsKept() {
        assertEquals("HTTP 404", TvErrors.reason(IOException("HTTP 404")))
        assertEquals("not an M3U playlist", TvErrors.reason(IOException("not an M3U playlist")))
        assertEquals("the file is too large", TvErrors.reason(IOException("the file is too large")))
        assertEquals(GuideUpdate.CUT, TvErrors.reason(IOException(GuideUpdate.CUT)))
    }

    @Test
    fun anythingElseIsTheFallback() {
        assertEquals(TvErrors.FAILED, TvErrors.reason(IOException("unexpected end of stream on http://192.0.2.5:8080/...")))
        assertEquals(TvErrors.FAILED, TvErrors.reason(IllegalStateException()))
        assertEquals(TvErrors.GUIDE_UNREADABLE, TvErrors.reason(RuntimeException("Unexpected token (position:TEXT x@1:1)"), TvErrors.GUIDE_UNREADABLE))
    }
}
