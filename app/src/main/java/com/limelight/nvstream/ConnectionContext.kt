package com.limelight.nvstream

import com.limelight.nvstream.http.ComputerDetails
import java.security.cert.X509Certificate
import javax.crypto.SecretKey

class ConnectionContext {
    var serverAddress: ComputerDetails.AddressTuple? = null
    var httpsPort: Int = 0
    var isNvidiaServerSoftware: Boolean = false
    var serverCert: X509Certificate? = null
    var streamConfig: StreamConfiguration? = null
    var connListener: NvConnectionListener? = null
    var riKey: SecretKey? = null
    var riKeyId: Int = 0

    // This is the version quad from the appversion tag of /serverinfo
    var serverAppVersion: String? = null
    var serverGfeVersion: String? = null
    var serverCodecModeSupport: Int = 0

    // This is the sessionUrl0 tag from /resume and /launch
    var rtspSessionUrl: String? = null

    var negotiatedWidth: Int = 0
    var negotiatedHeight: Int = 0
    var negotiatedHdr: Boolean = false

    var negotiatedRemoteStreaming: Int = 0
    var negotiatedPacketSize: Int = 0

    var videoCapabilities: Int = 0

    // 设备亮度范围
    var minBrightness: Int = 0
    var maxBrightness: Int = 0
    var maxAverageBrightness: Int = 0

    // 选择的显示器名称
    var displayName: String? = null
}
