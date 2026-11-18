# STX Control (@guucds) — regras do R8
-keepclassmembers class br.com.guucds.stxcontrol.BleBridge {
    @android.webkit.JavascriptInterface <methods>;
}
-keepattributes JavascriptInterface
-keep class br.com.guucds.stxcontrol.MainActivity { *; }
# remove nomes de arquivo/linha dos erros e reorganiza pacotes
-renamesourcefileattribute ""
-repackageclasses ''
-allowaccessmodification
-overloadaggressively
# remove todas as chamadas de log do APK final (Android e o nosso wrapper Lg): nenhuma pista no logcat
-assumenosideeffects class android.util.Log { *; }
-assumenosideeffects class br.com.guucds.stxcontrol.Lg { static *; }
