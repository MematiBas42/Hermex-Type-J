# Keep kotlinx.serialization generated serializers (release builds are not
# minified yet; rules kept ready for when minification is enabled).
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.AnnotationsKt

-keepclassmembers class kotlinx.serialization.json.** {
    *** Companion;
}
-keepclasseswithmembers class kotlinx.serialization.json.** {
    kotlinx.serialization.KSerializer serializer(...);
}
-keep,includedescriptorclasses class io.github.jcartu.hermex.**$$serializer { *; }
-keepclassmembers class io.github.jcartu.hermex.** {
    *** Companion;
}
-keepclasseswithmembers class io.github.jcartu.hermex.** {
    kotlinx.serialization.KSerializer serializer(...);
}
