package org.rama.service.document;

import java.security.PrivateKey;
import java.security.cert.Certificate;

public record SigningMaterial(Certificate[] chain, PrivateKey privateKey) {
}
