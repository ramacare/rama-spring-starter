package org.rama.service;

import org.bouncycastle.asn1.x500.X500Name;
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter;
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder;
import org.bouncycastle.jce.provider.BouncyCastleProvider;
import org.bouncycastle.operator.ContentSigner;
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder;

import java.io.ByteArrayInputStream;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.security.cert.*;
import java.security.cert.Certificate;
import java.security.spec.InvalidKeySpecException;
import java.security.spec.PKCS8EncodedKeySpec;
import java.util.*;

public class CertificateService {
    private static final String VAULT_CERTIFICATE_PATH = "certificates";
    private static final String ALGORITHM = "SHA256WithRSAEncryption";
    private static final String DN_TEMPLATE = "CN=%s, O=Mahidol University,OU=Ramathibodi Hospital, L=Bangkok, C=Thailand";

    static {
        Security.addProvider(new BouncyCastleProvider());
    }

    private final VaultService vaultService;

    public CertificateService(VaultService vaultService) {
        this.vaultService = vaultService;
    }

    public static KeyPair generateRSAKeyPair() throws Exception {
        KeyPairGenerator keyPairGenerator = KeyPairGenerator.getInstance("RSA", "BC");
        keyPairGenerator.initialize(2048);
        return keyPairGenerator.generateKeyPair();
    }

    public static X509Certificate generateRootCA(KeyPair keyPair) throws Exception {
        Date startDate = new Date();
        Date endDate = new Date(startDate.getTime() + (10 * 365 * 86400000L));

        X500Name issuer = new X500Name(String.format(DN_TEMPLATE, "Ramathibodi Hosptial"));
        X500Name subject = new X500Name(String.format(DN_TEMPLATE, "Ramathibodi Root CA"));
        BigInteger serial = BigInteger.valueOf(System.currentTimeMillis());

        JcaX509v3CertificateBuilder certBuilder = new JcaX509v3CertificateBuilder(issuer, serial, startDate, endDate, subject, keyPair.getPublic());
        ContentSigner signer = new JcaContentSignerBuilder(ALGORITHM).build(keyPair.getPrivate());

        return new JcaX509CertificateConverter().setProvider("BC").getCertificate(certBuilder.build(signer));
    }

    public static X509Certificate generateSignedCertificate(KeyPair keyPair, String commonName, int days, X509Certificate caCert, PrivateKey caPrivateKey) throws Exception {
        Date from = new Date();
        Date to = new Date(from.getTime() + (days * 86400000L));
        BigInteger serial = BigInteger.valueOf(System.currentTimeMillis());

        X500Name issuer = (caCert != null)
                ? new X500Name(caCert.getSubjectX500Principal().getName())
                : new X500Name(String.format(DN_TEMPLATE, commonName));

        JcaX509v3CertificateBuilder builder = new JcaX509v3CertificateBuilder(issuer, serial, from, to, new X500Name(String.format(DN_TEMPLATE, commonName)), keyPair.getPublic());

        ContentSigner signer = (caPrivateKey != null)
                ? new JcaContentSignerBuilder(ALGORITHM).build(caPrivateKey)
                : new JcaContentSignerBuilder(ALGORITHM).build(keyPair.getPrivate());

        return new JcaX509CertificateConverter().setProvider("BC").getCertificate(builder.build(signer));
    }

    public Map<String, Object> generateCertificate(String alias, String commonName, int days) throws Exception {
        Map<String, Object> rootCA = getRootCA();
        Certificate[] rootCAChain = stringToCertificates(rootCA.get("certificate").toString());
        X509Certificate rootCACert = (X509Certificate) rootCAChain[0];

        KeyPair keyPair = generateRSAKeyPair();
        X509Certificate cert = generateSignedCertificate(keyPair, commonName, days,
                rootCACert, base64ToPrivateKey(rootCA.get("privateKey").toString()));

        Certificate[] chainCertificates = new Certificate[rootCAChain.length + 1];
        chainCertificates[0] = cert;
        System.arraycopy(rootCAChain, 0, chainCertificates, 1, rootCAChain.length);

        Map<String, Object> certificate = certificateToMap(keyPair, chainCertificates);
        vaultService.store(VAULT_CERTIFICATE_PATH, alias, certificate);

        return certificate;
    }

    private static Map<String, Object> certificateToMap(KeyPair keyPair, Certificate[] cert) throws Exception {
        String encodedPrivateKey = Base64.getEncoder().encodeToString(keyPair.getPrivate().getEncoded());
        Map<String, Object> certificate = new HashMap<>();
        certificate.put("privateKey", encodedPrivateKey);
        certificate.put("certificate", certificatesToString(cert));
        return certificate;
    }

    public Map<String, Object> initializeRootCA() throws Exception {
        KeyPair keyPair = generateRSAKeyPair();
        X509Certificate rootCACert = generateRootCA(keyPair);

        Map<String, Object> certificate = certificateToMap(keyPair, new Certificate[]{rootCACert});
        vaultService.store(VAULT_CERTIFICATE_PATH, "rootCA", certificate);

        return certificate;
    }

    public Map<String, Object> getRootCA() throws Exception {
        Optional<Map<String, Object>> vaultRootCA = vaultService.retrieve(VAULT_CERTIFICATE_PATH, "rootCA");
        if (vaultRootCA.isPresent()) {
            Certificate[] certificates = stringToCertificates(vaultRootCA.get().get("certificate").toString());
            if (isCertificateValid(certificates)) return vaultRootCA.get();
        }
        return initializeRootCA();
    }

    public Map<String, Object> getCertificate(String alias, String commonName, int days) throws Exception {
        Optional<Map<String, Object>> vaultCertificate = vaultService.retrieve(VAULT_CERTIFICATE_PATH, alias);
        if (vaultCertificate.isPresent()) {
            Certificate[] certificates = stringToCertificates(vaultCertificate.get().get("certificate").toString());
            if (isCertificateValid(certificates)) return vaultCertificate.get();
        }
        return generateCertificate(alias, commonName, days);
    }

    public static String certificatesToString(Certificate[] certificates) throws Exception {
        StringBuilder certChainStr = new StringBuilder();
        for (Certificate cert : certificates) {
            byte[] encoded = cert.getEncoded();
            String base64Cert = Base64.getEncoder().encodeToString(encoded);
            certChainStr.append("-----BEGIN CERTIFICATE-----\n");
            certChainStr.append(base64Cert);
            certChainStr.append("\n-----END CERTIFICATE-----\n");
        }
        return certChainStr.toString();
    }

    public static Certificate[] stringToCertificates(String certificatesString) throws Exception {
        return stringToCertificatesList(certificatesString).toArray(X509Certificate[]::new);
    }

    public static List<X509Certificate> stringToCertificatesList(String certificatesString) throws Exception {
        CertificateFactory certificateFactory = CertificateFactory.getInstance("X.509");
        Collection<? extends Certificate> certificates = certificateFactory.generateCertificates(
                new ByteArrayInputStream(certificatesString.getBytes(StandardCharsets.UTF_8)));
        return certificates.stream().map(X509Certificate.class::cast).toList();
    }

    public static PrivateKey base64ToPrivateKey(String base64EncodedKey) throws NoSuchAlgorithmException, InvalidKeySpecException {
        byte[] keyData = Base64.getDecoder().decode(base64EncodedKey);
        PKCS8EncodedKeySpec spec = new PKCS8EncodedKeySpec(keyData);
        KeyFactory keyFactory = KeyFactory.getInstance("RSA");
        return keyFactory.generatePrivate(spec);
    }

    public static boolean isCertificateValid(Certificate cert) {
        if (cert instanceof X509Certificate x509Cert) {
            try {
                x509Cert.checkValidity();
                return true;
            } catch (CertificateExpiredException | CertificateNotYetValidException ignored) {
            }
        }
        return false;
    }

    public static boolean isCertificateValid(Certificate[] certificates) {
        return Arrays.stream(certificates).allMatch(CertificateService::isCertificateValid);
    }
}
