# App-specific R8 rules. The default Android rules come from proguard-android-optimize.txt.

# kotlinx.serialization: preserve companion objects and generated $$serializer classes
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.SerializationKt
-keep,includedescriptorclasses class knightlink.**$$serializer { *; }
-keepclassmembers class knightlink.** {
    *** Companion;
}
-keepclasseswithmembers class knightlink.** {
    kotlinx.serialization.KSerializer serializer(...);
}
