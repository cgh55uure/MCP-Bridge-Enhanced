# MCP Bridge Enhanced ProGuard Rules
-keepattributes *Annotation*
-keepattributes SourceFile,LineNumberTable

# Keep BoreClient
-keep class com.mcpbridge.enhanced.tunnel.** { *; }

# Keep service classes
-keep class com.mcpbridge.enhanced.keepalive.** { *; }
-keep class com.mcpbridge.enhanced.floatwindow.** { *; }