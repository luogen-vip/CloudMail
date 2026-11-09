# Keep JavaMail (jakarta.mail) classes
-keep class com.sun.mail.** { *; }
-keep class javax.mail.** { *; }
-keep class jakarta.mail.** { *; }
-keep class javax.activation.** { *; }
-dontwarn java.lang.invoke.StringConcatFactory
