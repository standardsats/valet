-dontobfuscate
-dontpreverify
-ignorewarnings
-dontnote
-dontwarn
-keepparameternames

-dontwarn scala.**

-keep class scala.collection.SeqLike { public protected *; }

-keep fr.acinq.eclair.blockchain.rpc.** { *; }

-keepattributes Signature,*Annotation*

-dontwarn javax.annotation.**
