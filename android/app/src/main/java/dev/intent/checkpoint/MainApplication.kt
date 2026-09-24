package dev.intent.checkpoint

import android.app.Application
import com.facebook.react.PackageList
import com.facebook.react.ReactApplication
import com.facebook.react.ReactHost
import com.facebook.react.ReactNativeApplicationEntryPoint.loadReactNative
import com.facebook.react.defaults.DefaultReactHost.getDefaultReactHost
import dev.intent.checkpoint.bridge.IntentCheckpointPackage
import dev.intent.checkpoint.engine.AppProcess

class MainApplication : Application(), ReactApplication {

  override val reactHost: ReactHost by lazy {
    getDefaultReactHost(
      context = applicationContext,
      packageList =
        PackageList(this).packages.apply {
          add(IntentCheckpointPackage())
        },
    )
  }

  override fun onCreate() {
    super.onCreate()
    // The :engine process (monitoring service, overlay, alarms) must never load Hermes/React:
    // it stays small, and outlives or survives RN crashes and kills (PRD §24, §46).
    if (AppProcess.isEngineProcess(this)) return
    loadReactNative(this)
  }
}
