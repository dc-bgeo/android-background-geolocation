# Keep this facade's public surface through the consuming app's R8 pass.
# The engine AAR (dev.bgeo:bgeo-android) ships its own consumer-rules.pro
# keeping com.bgeo.* and its manifest components - this file covers only
# the com.bgeo.sdk facade on top of it.
-keep public class com.bgeo.sdk.* { public *; }
