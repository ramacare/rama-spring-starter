package org.rama.service.document;

import com.itextpdf.forms.form.element.SignatureFieldAppearance;
import com.itextpdf.io.image.ImageData;
import com.itextpdf.io.image.ImageDataFactory;
import com.itextpdf.kernel.font.PdfFont;
import com.itextpdf.kernel.font.PdfFontFactory;
import com.itextpdf.kernel.geom.Rectangle;
import com.itextpdf.kernel.pdf.PdfReader;
import com.itextpdf.kernel.pdf.StampingProperties;
import com.itextpdf.layout.element.Div;
import com.itextpdf.layout.element.Image;
import com.itextpdf.layout.element.Paragraph;
import com.itextpdf.layout.element.Text;
import com.itextpdf.signatures.*;
import org.apache.xml.security.signature.XMLSignature;
import org.rama.util.XMLUtil;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;
import xades4j.algorithms.EnvelopedSignatureTransform;
import xades4j.production.*;
import xades4j.properties.DataObjectDesc;
import xades4j.properties.DataObjectFormatProperty;
import xades4j.providers.KeyingDataProvider;
import xades4j.providers.impl.HttpTsaConfiguration;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.security.PrivateKey;
import java.security.cert.Certificate;
import java.security.cert.X509Certificate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;

public abstract class AbstractSignService {
    private final ITSAClient tsaClient;
    private final HttpTsaConfiguration httpTsaConfiguration;
    private final byte[] fontBytes;
    private final byte[] logoBytes;

    protected AbstractSignService(ITSAClient tsaClient, HttpTsaConfiguration httpTsaConfiguration, String fontPath, String logoPath) {
        this.tsaClient = tsaClient;
        this.httpTsaConfiguration = httpTsaConfiguration;
        this.fontBytes = readClasspathBytesQuiet(fontPath);
        this.logoBytes = readClasspathBytesQuiet(logoPath);
    }

    protected abstract SigningMaterial resolveSigningMaterial(String alias, String commonName) throws Exception;

    public Mono<byte[]> signPdfMono(byte[] pdfBytes, String alias, String commonName) {
        return Mono.fromCallable(() -> signPdfBlocking(pdfBytes, alias, commonName)).subscribeOn(Schedulers.boundedElastic());
    }

    public Mono<byte[]> signXmlBytesMono(InputStream xmlInput, String alias, String commonName) {
        return Mono.fromCallable(() -> {
            Document doc = parseXmlSecure(xmlInput);
            Document signed = signXmlBlocking(doc, alias, commonName);
            return XMLUtil.documentToString(signed).getBytes(java.nio.charset.StandardCharsets.UTF_8);
        }).subscribeOn(Schedulers.boundedElastic());
    }

    public byte[] signPdfBlocking(byte[] pdfBytes, String alias, String commonName) throws Exception {
        SigningMaterial signingMaterial = resolveSigningMaterial(alias, commonName);
        Certificate[] chain = signingMaterial.chain();
        PrivateKey privateKey = signingMaterial.privateKey();

        IExternalSignature signature = new PrivateKeySignature(privateKey, "SHA256", "BC");
        IExternalDigest digest = new BouncyCastleDigest();

        try (ByteArrayInputStream in = new ByteArrayInputStream(pdfBytes);
             ByteArrayOutputStream out = new ByteArrayOutputStream();
             PdfReader reader = new PdfReader(in)) {
            PdfSigner signer = new PdfSigner(reader, out, new StampingProperties());
            Rectangle pageSize = signer.getDocument().getFirstPage().getPageSize();
            float width = 160;
            float height = 75;
            float x = pageSize.getRight() - width - 5;
            float y = 5;
            Rectangle rect = new Rectangle(x, y, width, height);

            signer.setPageNumber(1);
            signer.setPageRect(rect);

            SignatureFieldAppearance appearance = new SignatureFieldAppearance("Signature");
            appearance.setContent(buildSignatureDiv(chain[0], rect.getWidth(), rect.getHeight()));
            signer.setSignatureAppearance(appearance);
            signer.signDetached(digest, signature, chain, null, null, tsaClient, 0, PdfSigner.CryptoStandard.CMS);
            return out.toByteArray();
        }
    }

    public Document signXmlBlocking(Document document, String alias, String commonName) throws Exception {
        SigningMaterial signingMaterial = resolveSigningMaterial(alias, commonName);

        KeyingDataProvider provider = new KeyingDataProvider() {
            @Override
            public List<X509Certificate> getSigningCertificateChain() {
                return java.util.Arrays.stream(signingMaterial.chain()).map(X509Certificate.class::cast).toList();
            }

            @Override
            public PrivateKey getSigningKey(X509Certificate x509Certificate) {
                return signingMaterial.privateKey();
            }
        };

        Element elementToSign = document.getDocumentElement();
        DataObjectDesc obj = new DataObjectReference(elementToSign.hasAttribute("Id") ? "#" + elementToSign.getAttribute("Id") : "")
                .withTransform(new EnvelopedSignatureTransform())
                .withDataObjectFormat(new DataObjectFormatProperty("text/xml"));

        XadesSigningProfile profile = new XadesTSigningProfile(provider);
        if (httpTsaConfiguration != null) {
            profile.with(httpTsaConfiguration);
        }

        XadesSigner signer = profile
                .withSignatureAlgorithms(new SignatureAlgorithms().withSignatureAlgorithm("RSA", XMLSignature.ALGO_ID_SIGNATURE_RSA_SHA256))
                .newSigner();

        XadesSignatureResult result = signer.sign(new SignedDataObjects(obj), document.getDocumentElement());
        return result.getSignature().getDocument();
    }

    protected Div buildSignatureDiv(Certificate certificate, float width, float height) {
        Div div = new Div();
        String signerName = certificateSignerName(certificate);
        String location = certificateLocation(certificate);

        StringBuilder signatureText = new StringBuilder();
        String title = signerName == null ? "Digitally signed\n" : "Digitally signed by\n";
        if (signerName != null) {
            signatureText.append(signerName).append("\n");
        }
        signatureText.append(LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd H:mm:ss")));
        if (location != null) {
            signatureText.append("\n").append(location);
        }

        Paragraph paragraph = new Paragraph(new Text(title).setBold());
        paragraph.add(new Text(signatureText.toString()));
        paragraph.setMultipliedLeading(0.8F).setFontSize(8F);

        try {
            if (fontBytes != null) {
                PdfFont font = PdfFontFactory.createFont(fontBytes, PdfFontFactory.EmbeddingStrategy.PREFER_EMBEDDED);
                paragraph.setFont(font);
            }
        } catch (Exception ignored) {
        }

        try {
            if (logoBytes != null) {
                ImageData imageData = ImageDataFactory.createPng(logoBytes);
                float ws = (width * 0.25F) / imageData.getWidth();
                float hs = (height * 0.9F) / imageData.getHeight();
                float scale = Math.min(ws, hs);
                Image image = new Image(imageData);
                image.scale(scale, scale);
                image.setFixedPosition(0F, ((height - image.getImageScaledHeight()) / 2) - 3F);
                paragraph.setFixedPosition(image.getImageScaledWidth() + 3F, 5F, width - (image.getImageScaledWidth() + 3F));
                div.add(paragraph);
                div.add(image);
            } else {
                div.add(paragraph);
            }
        } catch (Exception e) {
            div.add(paragraph);
        }

        return div;
    }

    protected static Document parseXmlSecure(InputStream inputStream) throws Exception {
        try (InputStream is = inputStream) {
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            factory.setNamespaceAware(true);
            factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
            factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
            factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
            factory.setExpandEntityReferences(false);
            factory.setXIncludeAware(false);
            try { factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true); } catch (Exception ignore) {}
            try { factory.setFeature("http://xml.org/sax/features/external-general-entities", false); } catch (Exception ignore) {}
            try { factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false); } catch (Exception ignore) {}
            DocumentBuilder builder = factory.newDocumentBuilder();
            return builder.parse(is);
        }
    }

    private static String certificateSignerName(Certificate certificate) {
        CertificateInfo.X500Name x500name = CertificateInfo.getSubjectFields((X509Certificate) certificate);
        if (x500name == null) {
            return null;
        }
        String name = x500name.getField("CN");
        return name == null ? x500name.getField("E") : name;
    }

    private static String certificateLocation(Certificate certificate) {
        CertificateInfo.X500Name x500name = CertificateInfo.getSubjectFields((X509Certificate) certificate);
        if (x500name == null) {
            return null;
        }
        String location = x500name.getField("OU");
        if (location == null) {
            location = x500name.getField("L");
        }
        return location == null ? x500name.getField("C") : location;
    }

    private static byte[] readClasspathBytesQuiet(String path) {
        try (InputStream is = AbstractSignService.class.getResourceAsStream(path)) {
            return is == null ? null : is.readAllBytes();
        } catch (Exception e) {
            return null;
        }
    }
}
