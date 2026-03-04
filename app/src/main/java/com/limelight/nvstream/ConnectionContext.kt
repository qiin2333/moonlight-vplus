package com.limelight.nvstream

import com.limelight.nvstream.http.ComputerDetails
import java.security.cert.X509Certificate
import javax.crypto.SecretKey

class ConnectionContext {
    @JvmField var serverAddress: ComputerDetails.AddressTuple? = null
    @JvmField var httpsPort: Int = 0
    @JvmField var isNvidiaServerSoftware: Boolean = false
    @JvmField var serverCert: X509Certificate? = null
    @JvmField var streamConfig: StreamConfiguration? = null
    @JvmField var connListener: NvConnectionListener? = null
    @JvmField var riKey: SecretKey? = null
    @JvmField var riKeyId: Int = 0

    // This is the version quad from the appversion tag of /serverinfo
    @JvmField var serverAppVersion: String? = null
    @JvmField var serverGfeVersion: String? = null
    @JvmField var serverCodecModeSupport: Int = 0

    // This is the sessionUrl0 tag from /resume and /launch
    @JvmField var rtspSessionUrl: String? = null

    @JvmField var negotiatedWidth: Int = 0
    @JvmField var negotiatedHeight: Int = 0
    @JvmField var negotiatedHdr: Boolean = false

    @JvmField var negotiatedRemoteStreaming: Int = 0
    @JvmField var negotiatedPacketSize: Int = 0

    @JvmField var videoCapabilities: Int = 0

    // 设备亮度范围
    @JvmField var minBrightness: Int = 0
    @JvmField var maxBrightness: Int = 0
    @JvmField var maxAverageBrightness: Int = 0

    // 选择的显示器名称
    @JvmField var displayName: String? = null
}
