package top.cbug.adbx

import org.junit.Test
import org.junit.Assert.*
import top.cbug.adbx.util.WifiHelper

/**
 * Unit tests for the pure (Context-free) helpers. Logic that touches an
 * Android [android.content.Context] needs Robolectric or an instrumented
 * test; [WifiHelper.cleanSsid] is deliberately pure so it can be verified
 * here on the plain JVM.
 */
class WifiHelperCleanSsidTest {

    @Test
    fun stripsSurroundingDoubleQuotes() {
        assertEquals("MyWiFi", WifiHelper.cleanSsid("\"MyWiFi\""))
    }

    @Test
    fun stripsSurroundingSingleQuotes() {
        assertEquals("MyWiFi", WifiHelper.cleanSsid("'MyWiFi'"))
    }

    @Test
    fun trimsWhitespace() {
        assertEquals("Home 5G", WifiHelper.cleanSsid("  Home 5G  "))
    }

    @Test
    fun leavesBareSsidUntouched() {
        assertEquals("OpenNet", WifiHelper.cleanSsid("OpenNet"))
    }

    @Test
    fun collapsesSentinelsToEmpty() {
        assertEquals("", WifiHelper.cleanSsid("<unknown ssid>"))
        assertEquals("", WifiHelper.cleanSsid("0x"))
        assertEquals("", WifiHelper.cleanSsid("null"))
        assertEquals("", WifiHelper.cleanSsid(""))
        assertEquals("", WifiHelper.cleanSsid("   "))
    }

    @Test
    fun nullInputYieldsEmpty() {
        assertEquals("", WifiHelper.cleanSsid(null))
    }

    @Test
    fun keepsInteriorQuotesWhenNotSurrounding() {
        assertEquals("net\"5", WifiHelper.cleanSsid("net\"5"))
    }
}
