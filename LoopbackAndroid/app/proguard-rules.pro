# kotlinx.serialization
-keepattributes *Annotation*, InnerClasses
-keepclassmembers class name.gornostal.loopback.** {
    *** Companion;
}
-keepclasseswithmembers class name.gornostal.loopback.** {
    kotlinx.serialization.KSerializer serializer(...);
}
