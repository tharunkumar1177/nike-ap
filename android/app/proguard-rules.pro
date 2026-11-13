# proguard-rules.pro: R8 rules for the PadLink release build.
# All framework entry points (activities, service, application) are kept by the manifest-derived rules.
# Keep line numbers so CI-built release stack traces stay readable.
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile
