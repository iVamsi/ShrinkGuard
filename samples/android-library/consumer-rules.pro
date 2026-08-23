# Consumer rules for Android Crypto Library
-keepclassmembers class com.example.android.crypto.internal.KeyGeneratorImpl {
    public <init>();
    public byte[] generateKey();
}
