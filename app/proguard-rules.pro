# Morphix Launcher — ProGuard / R8 rules

# Keep line numbers for readable crash reports
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile

# Model classes are read/written via SharedPreferences and reflection-free copy(),
# but keep names stable for debugging
-keep class com.naua_morphix_launcher.app.model.** { *; }

# Custom views inflated from XML
-keep public class com.naua_morphix_launcher.app.views.** {
    public <init>(android.content.Context);
    public <init>(android.content.Context, android.util.AttributeSet);
    public <init>(android.content.Context, android.util.AttributeSet, int);
}

# Adapters used with ViewBinding / RecyclerView
-keep class com.naua_morphix_launcher.app.ui.** extends androidx.recyclerview.widget.RecyclerView$Adapter { *; }

# Accessibility / device admin / notification listener are referenced by name
# from AndroidManifest.xml and must keep their entry points.
-keep class com.naua_morphix_launcher.app.service.** { *; }
-keep class com.naua_morphix_launcher.app.receiver.** { *; }
-keep class com.naua_morphix_launcher.app.MorphixApp { *; }
-keep class com.naua_morphix_launcher.app.MainActivity { *; }

# System widgets are reflected on (AppWidgetProviderInfo spans, labels)
-dontwarn android.appwidget.**