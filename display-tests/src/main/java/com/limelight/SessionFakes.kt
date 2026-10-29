@file:Suppress("UNUSED_PARAMETER")

package com.limelight

import android.content.Context
import android.view.Display
import com.limelight.preferences.PreferenceConfiguration
import java.security.cert.X509Certificate

class StreamConfigResult(val config: Game.Contract)
object ComputerDetails { data class AddressTuple(val host: String, val port: Int) }
object NvHTTP {
    const val DEFAULT_HTTP_PORT = 47989
    const val DEFAULT_HTTPS_PORT = 47984
}
object PlatformBinding { fun getCryptoProvider(context: Context): Any = Any() }

class NvConnection(
    context: Context, host: ComputerDetails.AddressTuple, httpsPort: Int, uniqueId: String,
    pairName: String, val config: Game.Contract, crypto: Any, cert: X509Certificate?,
    val display: Display, displayName: String?, forceResume: Boolean
)

class ControllerHandler(
    game: Game, conn: NvConnection, listener: Game, prefs: PreferenceConfiguration,
    onTogglePerformanceOverlay: () -> Unit, onExitStream: () -> Unit
) {
    fun destroy() = Unit
    fun onSensorsReenabled() = Unit
}

class AudioServiceFixture { var controllerHandler: ControllerHandler? = null }
class VirtualControllerFixture { fun rebindControllerHandler(handler: ControllerHandler) = Unit }
class OrientationFixture { var connection: NvConnection? = null }
class TouchInputHandler { fun cancelNonRootTouchpad() = Unit }
