import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.*;
import java.security.interfaces.RSAPrivateCrtKey;
import java.security.spec.*;
import java.util.Base64;

/** Release helper: java tools/SignAdBlockRules.java rules.json private-key.pem rules.signed.json */
class SignAdBlockRules {
    public static void main(String[] args) throws Exception {
        if (args.length != 3) throw new IllegalArgumentException("Expected rules, PKCS#8 RSA key, new output file");
        Path input = Path.of(args[0]), privateKey = Path.of(args[1]), output = Path.of(args[2]);
        if (Files.size(input) > 256 * 1024 || Files.size(privateKey) > 32 * 1024)
            throw new IllegalArgumentException("Input is too large");
        byte[] rules = Files.readAllBytes(input);
        if (rules.length > 256 * 1024) throw new IllegalArgumentException("Rules are too large");
        String pem = Files.readString(privateKey, StandardCharsets.US_ASCII);
        if (!pem.startsWith("-----BEGIN PRIVATE KEY-----"))
            throw new IllegalArgumentException("Expected a PKCS#8 PEM RSA private key");
        byte[] der = Base64.getDecoder().decode(pem.replace("-----BEGIN PRIVATE KEY-----", "")
            .replace("-----END PRIVATE KEY-----", "").replaceAll("\\s", ""));
        KeyFactory factory = KeyFactory.getInstance("RSA");
        var key = (RSAPrivateCrtKey) factory.generatePrivate(new PKCS8EncodedKeySpec(der));
        if (key.getModulus().bitLength() < 2048 || key.getModulus().bitLength() > 8192)
            throw new IllegalArgumentException("RSA key must be 2048..8192 bits");
        Signature signer = Signature.getInstance("SHA256withRSA");
        signer.initSign(key);
        signer.update("TV-FLICK-ADBLOCK-RULES-V1\n".getBytes(StandardCharsets.UTF_8));
        signer.update(rules);
        String envelope = "{\"schemaVersion\":1,\"payload\":\"" + Base64.getEncoder().encodeToString(rules)
            + "\",\"signature\":\"" + Base64.getEncoder().encodeToString(signer.sign()) + "\"}\n";
        Files.writeString(output, envelope, StandardCharsets.UTF_8, StandardOpenOption.CREATE_NEW);
        var publicKey = factory.generatePublic(new RSAPublicKeySpec(key.getModulus(), key.getPublicExponent()));
        System.out.println("tvflick.adblockPublicKey=" + Base64.getEncoder().encodeToString(publicKey.getEncoded()));
        // Only public material is printed. Keep the private key outside the repository/distribution.
    }
}
