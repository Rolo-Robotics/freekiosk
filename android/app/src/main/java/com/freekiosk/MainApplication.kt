package com.freekiosk

import android.app.Application
import com.facebook.react.PackageList
import com.facebook.react.ReactApplication
import com.facebook.react.ReactHost
import com.facebook.react.ReactNativeApplicationEntryPoint.loadReactNative
import com.facebook.react.defaults.DefaultReactHost.getDefaultReactHost
import com.freekiosk.api.HttpServerPackage
import com.freekiosk.mqtt.MqttPackage
import com.freekiosk.payment.PaymentProviders
import com.freekiosk.payment.PaymentTerminalPackage
import com.freekiosk.printing.SilentPrintPackage

class MainApplication : Application(), ReactApplication {

  override val reactHost: ReactHost by lazy {
    getDefaultReactHost(
      context = applicationContext,
      packageList =
        PackageList(this).packages.apply {
          // Packages that cannot be autolinked yet can be added manually here
          add(KioskPackage())
          add(CertificatePackage())
          add(MotionDetectionPackage())
          add(AppLauncherPackage())
          add(OverlayPermissionPackage())
          add(LauncherPackage())
          add(OverlayServicePackage())
          add(SystemInfoPackage())
          add(UpdatePackage())
          add(ManagedAppInstallerPackage())
          add(SoundPlayerPackage())
          add(HttpServerPackage())
          add(MqttPackage())
          add(BlockingOverlayPackage())
          add(AutoBrightnessPackage())
          add(PrintPackage())
          add(SilentPrintPackage())
          add(AccessibilityPackage())
          add(FilePickerPackage())
          add(WifiControlPackage())
          add(BluetoothControlPackage())
          add(AudioControlPackage())
          add(FlashlightPackage())
          add(RotationControlPackage())
          add(PaymentTerminalPackage())
        },
    )
  }

  override fun onCreate() {
    super.onCreate()
    // Stripe Tap to Pay runs in a dedicated process that creates a second Application.
    // Starting React Native, the services and the receivers again in there would give
    // two kiosks fighting over one screen. Always false without -Ppayments.
    if (PaymentProviders.isInPaymentSdkProcess()) return
    PaymentProviders.onApplicationCreate(this)
    loadReactNative(this)
  }
}
