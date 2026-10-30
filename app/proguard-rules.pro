# Keep default rules; the app does not use reflection-based serialisation.

# JNI bridge: the native libtorrent library looks these classes up by name, and the SWIG
# layer builds them by reflection. Stripping or renaming them breaks loading the engine.
-keep class org.libtorrent4j.swig.libtorrent_jni { *; }
-keep class org.libtorrent4j.swig.** { *; }
-keep class org.libtorrent4j.** { *; }
