# La compilación de depuración no ofusca. Estas reglas cubren un release futuro.
-keep class com.google.mediapipe.** { *; }
-keep class com.google.protobuf.** { *; }
-dontwarn com.google.mediapipe.**
