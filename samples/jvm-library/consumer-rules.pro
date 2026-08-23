# ShrinkGuard Consumer Rules for Currency Library
-keepclassmembers class com.example.currency.internal.RateSerializer {
    public <init>();
    public java.lang.String serialize(com.example.currency.Money);
}
