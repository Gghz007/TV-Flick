package app.tvflick.core.update

import java.security.KeyFactory
import java.security.Signature
import java.security.interfaces.RSAPublicKey
import java.security.spec.X509EncodedKeySpec

internal actual fun verifyRsaSignature(payload: ByteArray, signature: ByteArray, publicKey: ByteArray): Boolean =
    runCatching {
        val key = KeyFactory.getInstance("RSA").generatePublic(X509EncodedKeySpec(publicKey)) as RSAPublicKey
        require(key.modulus.bitLength() in 2048..8192)
        Signature.getInstance("SHA256withRSA").run {
            initVerify(key)
            update(payload)
            verify(signature)
        }
    }.getOrDefault(false)
