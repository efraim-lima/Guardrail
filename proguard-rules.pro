# =============================================================================
# ProGuard — Regras de ofuscação para gateway-sensor
#
# Mantém o ponto de entrada Main e serialização segura;
# ofusca todo o resto (nomes de classes, métodos, campos).
# =============================================================================

# --------------------------------------------------------------------------
# Ponto de entrada — nunca ofuscar/remover a classe e o método main
# --------------------------------------------------------------------------
-keep public class Main {
    public static void main(java.lang.String[]);
}

# --------------------------------------------------------------------------
# Manter classes que são instanciadas por reflexão (BouncyCastle, pcap4j, slf4j)
# --------------------------------------------------------------------------
-keep class org.bouncycastle.** { *; }
-keep class org.pcap4j.** { *; }
-keep class org.slf4j.** { *; }
-keep class ch.qos.logback.** { *; }

# --------------------------------------------------------------------------
# Manter anotações e metadados necessários para o runtime do Java
# --------------------------------------------------------------------------
-keepattributes *Annotation*
-keepattributes Signature
-keepattributes Exceptions
-keepattributes EnclosingMethod
-keepattributes InnerClasses

# --------------------------------------------------------------------------
# Manter serialização (caso alguma classe implemente Serializable)
# --------------------------------------------------------------------------
-keepclassmembers class * implements java.io.Serializable {
    static final long serialVersionUID;
    private void writeObject(java.io.ObjectOutputStream);
    private void readObject(java.io.ObjectInputStream);
    java.lang.Object writeReplace();
    java.lang.Object readResolve();
}

# --------------------------------------------------------------------------
# Manter handlers de shutdown e threads nomeadas (usadas em Main.java)
# --------------------------------------------------------------------------
-keepclassmembers class * extends java.lang.Thread {
    public void run();
}

# --------------------------------------------------------------------------
# Manter Runnable implementados como classes anônimas/lambda
# --------------------------------------------------------------------------
-keepclassmembers class * implements java.lang.Runnable {
    public void run();
}

# --------------------------------------------------------------------------
# Ofuscação: ProGuard usa nomes curtos padrão (a, b, c...) automaticamente
# --------------------------------------------------------------------------
-ignorewarnings

# --------------------------------------------------------------------------
# Suprimir warnings de APIs internas do JDK usadas pelas dependências
# --------------------------------------------------------------------------
-dontwarn com.sun.**
-dontwarn sun.**

# --------------------------------------------------------------------------
# Configurações gerais
# --------------------------------------------------------------------------
-dontskipnonpubliclibraryclasses
-dontskipnonpubliclibraryclassmembers

# Otimizações compatíveis com Java 21 (sem bytecode-level peephole)
-optimizationpasses 3
-allowaccessmodification
-repackageclasses 'g'

# Remover chamadas de log em builds de produção (reduz superfície de ataque)
-assumenosideeffects class org.slf4j.Logger {
    public void debug(...);
    public void trace(...);
}

# Manter mensagens de erro intactas para diagnóstico mínimo em produção
-renamesourcefileattribute SourceFile
-keepattributes SourceFile,LineNumberTable
