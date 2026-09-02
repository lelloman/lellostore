package com.lelloman.store.selfadb;

import android.content.Context;
import android.os.Build;
import android.sun.misc.BASE64Encoder;
import android.sun.security.provider.X509Factory;
import android.sun.security.x509.AlgorithmId;
import android.sun.security.x509.CertificateAlgorithmId;
import android.sun.security.x509.CertificateExtensions;
import android.sun.security.x509.CertificateIssuerName;
import android.sun.security.x509.CertificateSerialNumber;
import android.sun.security.x509.CertificateSubjectName;
import android.sun.security.x509.CertificateValidity;
import android.sun.security.x509.CertificateVersion;
import android.sun.security.x509.CertificateX509Key;
import android.sun.security.x509.KeyIdentifier;
import android.sun.security.x509.SubjectKeyIdentifierExtension;
import android.sun.security.x509.X500Name;
import android.sun.security.x509.X509CertImpl;
import android.sun.security.x509.X509CertInfo;

import androidx.annotation.NonNull;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PrivateKey;
import java.security.SecureRandom;
import java.security.cert.Certificate;
import java.security.cert.CertificateFactory;
import java.security.spec.PKCS8EncodedKeySpec;
import java.util.Date;
import java.util.concurrent.TimeUnit;

import io.github.muntashirakon.adb.AbsAdbConnectionManager;

/** Debug-only ADB identity and connection holder for the self-ADB spike. */
public final class SelfAdbConnectionManager extends AbsAdbConnectionManager {
    private static SelfAdbConnectionManager instance;

    private final PrivateKey privateKey;
    private final Certificate certificate;

    public static synchronized SelfAdbConnectionManager getInstance(@NonNull Context context)
            throws Exception {
        if (instance == null) {
            instance = new SelfAdbConnectionManager(context.getApplicationContext());
        }
        return instance;
    }

    private SelfAdbConnectionManager(Context context) throws Exception {
        setApi(Build.VERSION.SDK_INT);
        setTimeout(15, TimeUnit.SECONDS);

        PrivateKey storedKey = readPrivateKey(context);
        Certificate storedCertificate = readCertificate(context);
        if (storedKey != null && storedCertificate != null) {
            privateKey = storedKey;
            certificate = storedCertificate;
            return;
        }

        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048, SecureRandom.getInstance("SHA1PRNG"));
        KeyPair pair = generator.generateKeyPair();
        privateKey = pair.getPrivate();

        String algorithm = "SHA256withRSA";
        Date notBefore = new Date();
        Date notAfter = new Date(System.currentTimeMillis() + TimeUnit.DAYS.toMillis(3650));
        X500Name subject = new X500Name("CN=LelloStore Self-ADB Spike");
        CertificateExtensions extensions = new CertificateExtensions();
        extensions.set(
                "SubjectKeyIdentifier",
                new SubjectKeyIdentifierExtension(new KeyIdentifier(pair.getPublic()).getIdentifier())
        );
        X509CertInfo info = new X509CertInfo();
        info.set("version", new CertificateVersion(2));
        info.set("serialNumber", new CertificateSerialNumber(1));
        info.set("algorithmID", new CertificateAlgorithmId(AlgorithmId.get(algorithm)));
        info.set("subject", new CertificateSubjectName(subject));
        info.set("issuer", new CertificateIssuerName(subject));
        info.set("key", new CertificateX509Key(pair.getPublic()));
        info.set("validity", new CertificateValidity(notBefore, notAfter));
        info.set("extensions", extensions);
        X509CertImpl generatedCertificate = new X509CertImpl(info);
        generatedCertificate.sign(privateKey, algorithm);
        certificate = generatedCertificate;

        writePrivateKey(context, privateKey);
        writeCertificate(context, certificate);
    }

    @NonNull
    @Override
    protected PrivateKey getPrivateKey() {
        return privateKey;
    }

    @NonNull
    @Override
    protected Certificate getCertificate() {
        return certificate;
    }

    @NonNull
    @Override
    protected String getDeviceName() {
        return "LelloStore Self-ADB Spike";
    }

    private static PrivateKey readPrivateKey(Context context) throws Exception {
        File file = identityFile(context, "self-adb-private.key");
        if (!file.exists()) return null;
        return KeyFactory.getInstance("RSA").generatePrivate(new PKCS8EncodedKeySpec(fileBytes(file)));
    }

    private static Certificate readCertificate(Context context) throws Exception {
        File file = identityFile(context, "self-adb-cert.pem");
        if (!file.exists()) return null;
        try (InputStream input = new FileInputStream(file)) {
            return CertificateFactory.getInstance("X.509").generateCertificate(input);
        }
    }

    private static void writePrivateKey(Context context, PrivateKey key) throws Exception {
        try (OutputStream output = new FileOutputStream(
                new File(context.getNoBackupFilesDir(), "self-adb-private.key"))) {
            output.write(key.getEncoded());
        }
    }

    private static void writeCertificate(Context context, Certificate cert) throws Exception {
        try (OutputStream output = new FileOutputStream(
                new File(context.getNoBackupFilesDir(), "self-adb-cert.pem"))) {
            BASE64Encoder encoder = new BASE64Encoder();
            output.write(X509Factory.BEGIN_CERT.getBytes(StandardCharsets.UTF_8));
            output.write('\n');
            encoder.encode(cert.getEncoded(), output);
            output.write('\n');
            output.write(X509Factory.END_CERT.getBytes(StandardCharsets.UTF_8));
        }
    }

    private static byte[] fileBytes(File file) throws Exception {
        byte[] bytes = new byte[(int) file.length()];
        try (InputStream input = new FileInputStream(file)) {
            int offset = 0;
            while (offset < bytes.length) {
                int count = input.read(bytes, offset, bytes.length - offset);
                if (count < 0) break;
                offset += count;
            }
        }
        return bytes;
    }

    private static File identityFile(Context context, String name) {
        File noBackupFile = new File(context.getNoBackupFilesDir(), name);
        File legacyFile = new File(context.getFilesDir(), name);
        if (!noBackupFile.exists() && legacyFile.exists()) {
            // Preserve identities created by early versions of the spike while moving them out of backup.
            if (!legacyFile.renameTo(noBackupFile)) return legacyFile;
        }
        return noBackupFile;
    }
}
