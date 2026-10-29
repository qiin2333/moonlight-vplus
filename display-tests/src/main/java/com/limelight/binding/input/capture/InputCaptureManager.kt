package com.limelight.binding.input.capture
object InputCaptureManager {
 var beforeExternalCreate:(()->Unit)?=null
 fun getInputCaptureProvider(activity:android.app.Activity,listener:Any):InputCaptureProvider=InputCaptureProvider()
 fun getInputCaptureProviderForExternalDisplay(activity:android.app.Activity,listener:Any):InputCaptureProvider {
  beforeExternalCreate?.invoke()
  return InputCaptureProvider()
 }
}
