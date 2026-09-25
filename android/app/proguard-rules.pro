# Kotlin serialization supplies its own consumer rules; no reflection-based DTO parsing.

# ML Kit discovers registrars by manifest class name and invokes their no-arg constructors.
# firebase-components 16.1.0 keeps the classes but not these constructors in R8 full mode.
-keep class com.google.mlkit.** implements com.google.firebase.components.ComponentRegistrar {
    public <init>();
}
