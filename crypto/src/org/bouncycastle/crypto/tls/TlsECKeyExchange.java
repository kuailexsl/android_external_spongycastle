package org.bouncycastle.crypto.tls;

import java.io.IOException;
import java.io.OutputStream;
import java.math.BigInteger;

import org.bouncycastle.asn1.DERBitString;
import org.bouncycastle.asn1.x509.KeyUsage;
import org.bouncycastle.asn1.x509.SubjectPublicKeyInfo;
import org.bouncycastle.asn1.x509.X509CertificateStructure;
import org.bouncycastle.asn1.x509.X509Extension;
import org.bouncycastle.asn1.x509.X509Extensions;
import org.bouncycastle.crypto.AsymmetricCipherKeyPair;
import org.bouncycastle.crypto.CipherParameters;
import org.bouncycastle.crypto.agreement.ECDHBasicAgreement;
import org.bouncycastle.crypto.generators.ECKeyPairGenerator;
import org.bouncycastle.crypto.params.AsymmetricKeyParameter;
import org.bouncycastle.crypto.params.ECDomainParameters;
import org.bouncycastle.crypto.params.ECKeyGenerationParameters;
import org.bouncycastle.crypto.params.ECPublicKeyParameters;
import org.bouncycastle.crypto.params.RSAKeyParameters;
import org.bouncycastle.crypto.util.PublicKeyFactory;
import org.bouncycastle.util.BigIntegers;

/**
 * Base class for EC key exchange algorithms (see RFC 4492)
 */
abstract class TlsECKeyExchange implements TlsKeyExchange
{

    protected TlsProtocolHandler handler;
    protected CertificateVerifyer verifyer;
    protected short keyExchange;
    protected TlsSigner tlsSigner;

    protected AsymmetricKeyParameter serverPublicKey;

    protected AsymmetricCipherKeyPair clientEphemeralKeyPair;
    protected ECPublicKeyParameters serverEphemeralPublicKey;

    protected Certificate clientCert;
    protected AsymmetricKeyParameter clientPrivateKey = null;

    TlsECKeyExchange(TlsProtocolHandler handler, CertificateVerifyer verifyer, short keyExchange,
    // TODO Replace with an interface e.g. TlsClientAuth
        Certificate clientCert, AsymmetricKeyParameter clientPrivateKey)
    {
        switch (keyExchange)
        {
            case KE_ECDHE_RSA:
                this.tlsSigner = new TlsRSASigner();
                break;
            case KE_ECDHE_ECDSA:
                this.tlsSigner = new TlsECDSASigner();
                break;
            case KE_ECDH_RSA:
            case KE_ECDH_ECDSA:
                this.tlsSigner = null;
                break;
            default:
                throw new IllegalArgumentException("unsupported key exchange algorithm");
        }

        this.handler = handler;
        this.verifyer = verifyer;
        this.keyExchange = keyExchange;
        this.clientCert = clientCert;
        this.clientPrivateKey = clientPrivateKey;
    }

    public void skipServerCertificate() throws IOException
    {
        throw new TlsFatalAlert(AlertDescription.unexpected_message);
    }

    public void processServerCertificate(Certificate serverCertificate) throws IOException
    {
        X509CertificateStructure x509Cert = serverCertificate.certs[0];
        SubjectPublicKeyInfo keyInfo = x509Cert.getSubjectPublicKeyInfo();

        try
        {
            this.serverPublicKey = PublicKeyFactory.createKey(keyInfo);
        }
        catch (RuntimeException e)
        {
            throw new TlsFatalAlert(AlertDescription.unsupported_certificate);
        }

        // Sanity check the PublicKeyFactory
        if (this.serverPublicKey.isPrivate())
        {
            throw new TlsFatalAlert(AlertDescription.internal_error);
        }

        // TODO 
        /*
         * Perform various checks per RFC2246 7.4.2: "Unless otherwise specified, the
         * signing algorithm for the certificate must be the same as the algorithm for the
         * certificate key."
         */

        // TODO Should the 'instanceof' tests be replaces with stricter checks on keyInfo.getAlgorithmId()?

        switch (this.keyExchange)
        {
            case KE_ECDH_ECDSA:
                if (!(this.serverPublicKey instanceof ECPublicKeyParameters))
                {
                    throw new TlsFatalAlert(AlertDescription.certificate_unknown);
                }
                validateKeyUsage(x509Cert, KeyUsage.keyAgreement);
                // TODO The algorithm used to sign the certificate should be ECDSA.
//                x509Cert.getSignatureAlgorithm();
                break;
            case KE_ECDHE_ECDSA:
                if (!(this.serverPublicKey instanceof ECPublicKeyParameters))
                {
                    throw new TlsFatalAlert(AlertDescription.certificate_unknown);
                }
                validateKeyUsage(x509Cert, KeyUsage.digitalSignature);
                break;
            case KE_ECDH_RSA:
                if (!(this.serverPublicKey instanceof ECPublicKeyParameters))
                {
                    throw new TlsFatalAlert(AlertDescription.certificate_unknown);
                }
                validateKeyUsage(x509Cert, KeyUsage.keyAgreement);
                // TODO The algorithm used to sign the certificate should be RSA.
//              x509Cert.getSignatureAlgorithm();
                break;
            case KE_ECDHE_RSA:
                if (!(this.serverPublicKey instanceof RSAKeyParameters))
                {
                    throw new TlsFatalAlert(AlertDescription.certificate_unknown);
                }
                validateKeyUsage(x509Cert, KeyUsage.digitalSignature);
                break;
            default:
                throw new TlsFatalAlert(AlertDescription.unsupported_certificate);
        }

        /*
         * Verify them.
         */
        if (!this.verifyer.isValid(serverCertificate.getCerts()))
        {
            throw new TlsFatalAlert(AlertDescription.user_canceled);
        }
    }

    protected boolean areOnSameCurve(ECDomainParameters a, ECDomainParameters b)
    {
        // TODO Move to ECDomainParameters.equals() or other utility method?
        return a.getCurve().equals(b.getCurve())
            && a.getG().equals(b.getG())
            && a.getN().equals(b.getN())
            && a.getH().equals(b.getH());
    }

    protected void generateEphemeralClientKeyExchange(ECPublicKeyParameters otherPublicKey, OutputStream os) throws IOException
    {
        clientEphemeralKeyPair = generateECKeyPair(otherPublicKey.getParameters());
        byte[] keData = externalizeKey((ECPublicKeyParameters)clientEphemeralKeyPair.getPublic());
        TlsUtils.writeUint24(keData.length + 1, os);
        TlsUtils.writeOpaque8(keData, os);
    }

    protected byte[] calculateECDHBasicAgreement(ECPublicKeyParameters publicKey,
        CipherParameters privateKey)
    {
        ECDHBasicAgreement basicAgreement = new ECDHBasicAgreement();
        basicAgreement.init(privateKey);
        BigInteger agreement = basicAgreement.calculateAgreement(publicKey);
        return BigIntegers.asUnsignedByteArray(agreement);
    }

    protected void validateKeyUsage(X509CertificateStructure c, int keyUsageBits) throws IOException
    {
        X509Extensions exts = c.getTBSCertificate().getExtensions();
        if (exts != null)
        {
            X509Extension ext = exts.getExtension(X509Extensions.KeyUsage);
            if (ext != null)
            {
                DERBitString ku = KeyUsage.getInstance(ext);
                int bits = ku.getBytes()[0] & 0xff;
                if ((bits & keyUsageBits) != keyUsageBits)
                {
                    throw new TlsFatalAlert(AlertDescription.certificate_unknown);
                }
            }
        }
    }

    private byte[] externalizeKey(ECPublicKeyParameters keyParameters) throws IOException
    {
        // TODO Add support for compressed encoding and SPF extension

        /*
         * RFC 4492 5.7. ...an elliptic curve point in uncompressed or compressed format.
         * Here, the format MUST conform to what the server has requested through a
         * Supported Point Formats Extension if this extension was used, and MUST be
         * uncompressed if this extension was not used.
         */
        return keyParameters.getQ().getEncoded();
    }

    private AsymmetricCipherKeyPair generateECKeyPair(ECDomainParameters parameters)
    {
        ECKeyPairGenerator keyPairGenerator = new ECKeyPairGenerator();
        ECKeyGenerationParameters keyGenerationParameters = new ECKeyGenerationParameters(
            parameters, handler.getRandom());

        keyPairGenerator.init(keyGenerationParameters);
        AsymmetricCipherKeyPair keyPair = keyPairGenerator.generateKeyPair();
        return keyPair;
    }
}
