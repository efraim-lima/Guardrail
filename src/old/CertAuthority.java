import org.bouncycastle.asn1.x500.X500Name;
import org.bouncycastle.asn1.x509.BasicConstraints;
import org.bouncycastle.asn1.x509.Extension;
import org.bouncycastle.asn1.x509.GeneralName;
import org.bouncycastle.asn1.x509.GeneralNames;
import org.bouncycastle.cert.X509CertificateHolder;
import org.bouncycastle.cert.X509v3CertificateBuilder;
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter;
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder;
import org.bouncycastle.operator.ContentSigner;
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder;

import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import java.math.BigInteger;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.KeyStore;
import java.security.PrivateKey;
import java.security.SecureRandom;
import java.security.cert.Certificate;
import java.security.cert.X509Certificate;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Date;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

// NOTA: Esta classe é usada apenas por TransparentTLSInterceptor, que não está sendo instanciado
// em Main.java. Portanto, CertAuthority não participa do fluxo de execução atual.
// Para ativar: instanciar em Main com a CA carregada do keystore e passar ao TransparentTLSInterceptor.

/**
 * CertAuthority - Geração de certificados folha dinâmicos por hostname.
 *
 * Abordagem BurpSuite: para cada host interceptado, gera um certificado TLS
 * leaf assinado pela CA interna do Gateway. O cliente (AgentK/Python) recebe
 * um cert válido para "api.openai.com" assinado por uma CA de confiança
 * (a CA do Gateway, que deve estar instalada no trust store do container).
 *
 * O Bouncy Castle (bcpkix + bcprov) já está em build.gradle.
 */
public class CertAuthority {

    private static final String LOG_PREFIX = "[CertAuthority]";

    private final X509Certificate caCert;
    private final PrivateKey caKey;
    private final SecureRandom rng = new SecureRandom();

    // Cache de SSLContext por hostname - evita gerar chave RSA a cada conexão
    private final Map<String, SSLContext> contextCache = new ConcurrentHashMap<>();

    public CertAuthority(X509Certificate caCert, PrivateKey caKey) {
        this.caCert = caCert;
        this.caKey = caKey;
    }

    /**
     * Retorna um SSLContext com certificado leaf assinado para o hostname dado.
     * Resultado é cacheado – geração de chave RSA ocorre apenas uma vez por host.
     *
     * @param host hostname do SNI (ex: "api.openai.com")
     * @return SSLContext configurado para se apresentar como 'host'
     */
    public SSLContext contextForHost(String host) throws Exception {
        String key = host == null ? "_fallback_" : host.toLowerCase(Locale.ROOT);
        SSLContext cached = contextCache.get(key);
        if (cached != null) {
            return cached;
        }
        // computeIfAbsent não aceita checked exceptions; geramos fora e inserimos
        SSLContext fresh = buildContextForHost(key);
        SSLContext existing = contextCache.putIfAbsent(key, fresh);
        return existing != null ? existing : fresh;
    }

    // -----------------------------------------------------------------------
    // Geração de certificado leaf
    // -----------------------------------------------------------------------

    private SSLContext buildContextForHost(String host) throws Exception {
        log("Gerando certificado leaf para: " + host);

        // 1. Par de chaves RSA 2048 para o certificado leaf
        KeyPairGenerator kpg = KeyPairGenerator.getInstance("RSA");
        kpg.initialize(2048, rng);
        KeyPair leafKeys = kpg.generateKeyPair();

        // 2. Montar certificado X.509 v3
        X500Name issuerName = new X500Name(caCert.getSubjectX500Principal().getName());
        X500Name subjectName = new X500Name("CN=" + host);
        BigInteger serial = BigInteger.valueOf(Math.abs(rng.nextLong())).add(BigInteger.ONE);
        Date notBefore = Date.from(Instant.now().minus(1, ChronoUnit.DAYS));
        Date notAfter  = Date.from(Instant.now().plus(397, ChronoUnit.DAYS));

        X509v3CertificateBuilder builder = new JcaX509v3CertificateBuilder(
                issuerName, serial, notBefore, notAfter, subjectName, leafKeys.getPublic()
        );

        // SAN: dNSName para validação de hostname no cliente
        GeneralName sanEntry = new GeneralName(GeneralName.dNSName, host);
        builder.addExtension(Extension.subjectAlternativeName, false,
                new GeneralNames(sanEntry));

        // basicConstraints = false (leaf, não CA)
        builder.addExtension(Extension.basicConstraints, true,
                new BasicConstraints(false));

        // 3. Assinar com a chave privada da CA
        ContentSigner signer = new JcaContentSignerBuilder("SHA256withRSA").build(caKey);
        X509CertificateHolder holder = builder.build(signer);
        X509Certificate leafCert = new JcaX509CertificateConverter().getCertificate(holder);

        // 4. KeyStore com cadeia: [leafCert, caCert]
        KeyStore ks = KeyStore.getInstance("PKCS12");
        ks.load(null, null);
        ks.setKeyEntry("leaf", leafKeys.getPrivate(), new char[0],
                new Certificate[]{leafCert, caCert});

        // 5. SSLContext com o leaf cert como identidade do servidor
        KeyManagerFactory kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
        kmf.init(ks, new char[0]);

        SSLContext ctx = SSLContext.getInstance("TLS");
        ctx.init(kmf.getKeyManagers(), null, null);

        log("Certificado leaf gerado e cacheado para: " + host);
        return ctx;
    }

    // -----------------------------------------------------------------------
    // Logging
    // -----------------------------------------------------------------------

    private static void log(String msg) {
        System.out.println(LOG_PREFIX + " " + msg);
    }
}
