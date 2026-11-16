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
-assumenosideeffects class android.util.Log { *; }
