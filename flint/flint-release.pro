# Native Qt/JNI and Go bridges use class/member names and reflection. Retain
# their entry points and serialization models, including every Flint service.
-keep class org.qtproject.** { *; }
-keep class org.amnezia.** { *; }
-keep class go.** { *; }
-keep class net.openvpn.** { *; }
-keep class com.google.android.libraries.barhopper.** { *; }
-keepclasseswithmembernames,includedescriptorclasses class * { native <methods>; }
-keepattributes *Annotation*,Signature,InnerClasses,EnclosingMethod
# Retain third-party JNI/reflective names. Dependency consumer rules still
# control reachability; no protocol, camera or barcode module is excluded.
-dontobfuscate
