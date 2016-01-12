/*
 * Copyright 2006-2016 ICEsoft Technologies Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the
 * License. You may obtain a copy of the License at
 *
 *        http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an "AS
 * IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either
 * express or implied. See the License for the specific language
 * governing permissions and limitations under the License.
 */
package org.icepdf.core.pobjects.acroform.signature;

import org.bouncycastle.asn1.*;
import org.bouncycastle.asn1.cms.Attribute;
import org.bouncycastle.asn1.cms.AttributeTable;
import org.bouncycastle.asn1.cms.ContentInfo;
import org.bouncycastle.asn1.pkcs.PKCSObjectIdentifiers;
import org.bouncycastle.jce.provider.X509CertParser;
import org.bouncycastle.tsp.TimeStampToken;
import org.bouncycastle.x509.util.StreamParsingException;
import org.icepdf.core.io.SeekableInput;
import org.icepdf.core.pobjects.Name;
import org.icepdf.core.pobjects.acroform.SignatureDictionary;
import org.icepdf.core.pobjects.acroform.SignatureFieldDictionary;
import org.icepdf.core.pobjects.acroform.signature.exceptions.SignatureIntegrityException;

import javax.security.auth.x500.X500Principal;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.math.BigInteger;
import java.security.*;
import java.security.cert.Certificate;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Enumeration;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * PKCS#1 and PKCS#7 are fairly close from a verification point of view so we'll use this class for common
 * functionality between PKCS#1 and PKCS#7.
 */
public abstract class AbstractPkcsValidator implements Validator {

    private static final Logger logger =
            Logger.getLogger(AbstractPkcsValidator.class.toString());

    // data object descriptor codes.
    public static final String ID_DATA_OBJECT_IDENTIFIER = PKCSObjectIdentifiers.data.getId();
    public static final String ID_SIGNED_DATA_OBJECT_IDENTIFIER = PKCSObjectIdentifiers.signedData.getId();
    public static final String ID_ENVELOPED_DATA_OBJECT_IDENTIFIER = PKCSObjectIdentifiers.envelopedData.getId();
    public static final String ID_DIGESTED_DATA_OBJECT_IDENTIFIER = PKCSObjectIdentifiers.digestedData.getId();
    public static final String ID_ENCRYPTED_DATA_OBJECT_IDENTIFIER = PKCSObjectIdentifiers.encryptedData.getId();

    private static final String ALGORITHM_WITH = "with";

    // signature dictionary of signature do verify
    protected SignatureFieldDictionary signatureFieldDictionary;

    // signer certificate
    protected X509Certificate signerCertificate;
    // digests used for verification
    protected String digestAlgorithmIdentifier;
    protected String signatureAlgorithmIdentifier;
    // PKCS
    protected ASN1Set signedAttributesSequence;
    protected byte[] encapsulatedContentInfoData;
    protected byte[] messageDigest;
    protected byte[] signatureValue;

    // validity checks.
    private boolean isDocumentModified = true;
    // todo validate this properties.
    private boolean isCertificateTrusted;
    private boolean isSignerTimeValid;
    private boolean isValidationTimeValid;

    protected boolean initialized;

    public AbstractPkcsValidator(SignatureFieldDictionary signatureFieldDictionary) throws SignatureIntegrityException {
        this.signatureFieldDictionary = signatureFieldDictionary;
        if (!initialized) {
            init();
        }
    }

    /**
     * Runs a series of tests to try and determine the validity o
     *
     * @throws SignatureIntegrityException
     */
    public abstract void validate() throws SignatureIntegrityException;

    protected void announceSignatureType(SignatureDictionary signatureDictionary) {
        if (logger.isLoggable(Level.FINE)) {
            Name preferredHandler = signatureDictionary.getFilter();
            Name encoding = signatureDictionary.getSubFilter();
            if (logger.isLoggable(Level.FINER)) {
                logger.finer("Signature Handler: " + preferredHandler);
                logger.finer("         Encoding: " + encoding);
                logger.finer("Starting Validation");
            }
        }
    }

    /**
     * SignedData ::= SEQUENCE {
     * 0, version CMSVersion,
     * 1, digestAlgorithms DigestAlgorithmIdentifiers,
     * 2, encapContentInfo EncapsulatedContentInfo,
     * 3, certificates [0] IMPLICIT CertificateSet OPTIONAL,
     * 4, crls [1] IMPLICIT RevocationInfoChoices OPTIONAL,
     * 5, signerInfos SignerInfos }
     * <p/>
     * DigestAlgorithmIdentifiers ::= SET OF DigestAlgorithmIdentifier
     * SignerInfos ::= SET OF SignerInfo
     */
    protected void parseSignerData(ASN1Sequence signedData, byte[] cmsData) throws SignatureIntegrityException {
        // digest algorithms ID, not currently using them but useful for debug.
        if (logger.isLoggable(Level.FINER)) {
            // should always be 1.
            int cmsVersion = ((ASN1Integer) signedData.getObjectAt(0)).getValue().intValue();
            logger.finer("CMS version: " + cmsVersion);
            Enumeration<ASN1Sequence> enumeration = ((ASN1Set) signedData.getObjectAt(1)).getObjects();
            while (enumeration.hasMoreElements()) {
                String objectId = ((ASN1ObjectIdentifier) enumeration.nextElement().getObjectAt(0)).getId();
                try {
                    String digestAlgorithmName = AlgorithmIdentifier.getDigestAlgorithmName(objectId);
                    MessageDigest tmp = AlgorithmIdentifier.getDigestInstance(objectId, null);
                    logger.finer("DigestAlgorithmIdentifiers: " + digestAlgorithmName + " " + objectId);
                    logger.finer(tmp.toString());
                } catch (Throwable ex) {
                    logger.log(Level.WARNING, "Error finding iod: " + objectId, ex);
                }
            }
        }
        /**
         * EncapsulatedContentInfo ::= SEQUENCE {
         *    eContentType ContentType,
         *    eContent [0] EXPLICIT OCTET STRING OPTIONAL }
         *
         * ContentType ::= OBJECT IDENTIFIER
         */
        encapsulatedContentInfoData = null;
        ASN1Sequence encapsulatedContentInfo = (ASN1Sequence) signedData.getObjectAt(2);
        // grab just the first definitions, as we are looking for encapuslated data for PKCS7.sha1.
        if (encapsulatedContentInfo.size() >= 2) {
            // should still be iso(1) member-body(2) us(840) rsadsi(113549) pkcs(1) pkcs7(7) 1 ...
            ASN1ObjectIdentifier eObjectIdentifier = (ASN1ObjectIdentifier) encapsulatedContentInfo.getObjectAt(0);
            String eObjectIdentifierId = eObjectIdentifier.getId();
            if (logger.isLoggable(Level.FINER)) {
                logger.finer("EncapsulatedContentInfo: " + eObjectIdentifierId + " " +
                        Pkcs7Validator.getObjectIdName(eObjectIdentifierId));
            }
            // should be octets encode as pkcs#7
            ASN1OctetString eContent = (ASN1OctetString) ((ASN1TaggedObject) encapsulatedContentInfo.getObjectAt(1))
                    .getObject();
            // shows up in pkcs7.sha1 only
            encapsulatedContentInfoData = eContent.getOctets();
            if (logger.isLoggable(Level.FINER)) {
                logger.finer("EncapsulatedContentInfo Data " + eContent.toString());
            }
        } else if (encapsulatedContentInfo.size() == 1) {
            if (logger.isLoggable(Level.FINER)) {
                ASN1ObjectIdentifier eObjectIdentifier = (ASN1ObjectIdentifier) encapsulatedContentInfo.getObjectAt(0);
                String eObjectIdentifierId = eObjectIdentifier.getId();
                logger.finer("EncapsulatedContentInfo size is 1: " + eObjectIdentifierId + " " +
                        Pkcs7Validator.getObjectIdName(eObjectIdentifierId));
            }
        }

        // grab the signer info.
        ASN1Sequence signerInfo = parseCertificateData(cmsData, signedData);
        // DigestAlgorithmIdentifier ::= AlgorithmIdentifier
        digestAlgorithmIdentifier = ((ASN1ObjectIdentifier)
                ((ASN1Sequence) signerInfo.getObjectAt(2)).getObjectAt(0)).getId();

        // signedAttrs [0] IMPLICIT SignedAttributes OPTIONAL,
        // signedAttrs is optional so we look for the occurrence
        //
        // SignedAttributes ::= SET SIZE (1..MAX) OF Attribute
        //
        // Attribute ::= SEQUENCE {
        //    attrType OBJECT IDENTIFIER,
        //    attrValues SET OF AttributeValue }
        //
        // AttributeValue ::= ANY
        // SignatureValue ::= OCTET STRING
        int nextEntry = 3;
        messageDigest = null;
        ASN1TaggedObject signedAttributes;
        signedAttributesSequence = null;
        if (signerInfo.getObjectAt(nextEntry) instanceof ASN1TaggedObject) {
            signedAttributes = (ASN1TaggedObject) signerInfo.getObjectAt(nextEntry);
            signedAttributesSequence = ASN1Set.getInstance(signedAttributes, false);
            for (int i = 0, max = signedAttributesSequence.size(); i < max; ++i) {
                // attribute type/value pair.
                ASN1Sequence attributePair = (ASN1Sequence) signedAttributesSequence.getObjectAt(i);
                // mainly just looking for the message digest.
                if (((ASN1ObjectIdentifier) attributePair.getObjectAt(0)).getId().equals(
                        PKCSObjectIdentifiers.pkcs_9_at_messageDigest.getId())) {
                    ASN1Set set = (ASN1Set) attributePair.getObjectAt(1);
                    messageDigest = ((ASN1OctetString) set.getObjectAt(0)).getOctets();
                }
                // todo further work for timestamp verification.
                if (((ASN1ObjectIdentifier) attributePair.getObjectAt(0)).getId().equals(
                        PKCSObjectIdentifiers.pkcs_9_at_signingTime.getId())) {
                    ASN1Set set = (ASN1Set) attributePair.getObjectAt(1);
                    ASN1UTCTime signerTime = ((ASN1UTCTime) set.getObjectAt(0));
                }
                // more attributes to come.
            }
            if (messageDigest == null) {
                throw new SignatureIntegrityException("Message Digest can nut be null");
            }
            ++nextEntry;
        }
        // signatureAlgorithm SignatureAlgorithmIdentifier,
        signatureAlgorithmIdentifier = ((ASN1ObjectIdentifier) ((ASN1Sequence) signerInfo.getObjectAt(nextEntry))
                .getObjectAt(0)).getId();
        nextEntry++;
        // signature SignatureValue
        signatureValue = ((ASN1OctetString) signerInfo.getObjectAt(nextEntry)).getOctets();
        nextEntry++;
        // unsignedAttrs [1] IMPLICIT UnsignedAttributes OPTIONAL
        // once again optional so we check to see if the entry is available.
        if (nextEntry < signerInfo.size() && signerInfo.getObjectAt(nextEntry) instanceof ASN1TaggedObject) {
            ASN1TaggedObject unsignedAttributes = (ASN1TaggedObject) signerInfo.getObjectAt(nextEntry);
            ASN1Set unsignedAttributeSequence = ASN1Set.getInstance(unsignedAttributes, false);
            AttributeTable attributeTable = new AttributeTable(unsignedAttributeSequence);
            Attribute timeStamp = attributeTable.get(PKCSObjectIdentifiers.id_aa_signatureTimeStampToken);
            if (timeStamp != null && timeStamp.getAttrValues().size() > 0) {
                ASN1Set attributeValues = timeStamp.getAttrValues();
                ASN1Sequence tokenSequence = ASN1Sequence.getInstance(attributeValues.getObjectAt(0));
                ContentInfo contentInfo = ContentInfo.getInstance(tokenSequence);
                // todo further work for timestamp verification.
                try {
                    TimeStampToken timeStampToken = new TimeStampToken(contentInfo);
                } catch (Throwable e1) {
//                    throw new SignatureIntegrityException("Valid TimeStamp could now be created");
                }
            }
        }
    }

    private ASN1Sequence parseCertificateData(byte[] cmsData, ASN1Sequence signedData) throws SignatureIntegrityException {

        // Next two entries are optional.
        // 3, certificates [0] IMPLICIT CertificateSet OPTIONAL,
        // crls [1] IMPLICIT RevocationInfoChoices OPTIONAL,
        // Most of our example seem to have what looks like a CertificateSet but I haven't had much luck finding
        // a specific format to follow ot parse out the data.
        // CertificatSet is defined as:<br/>
        // The CertificateSet type provides a set of certificates. It is
        // intended that the set be sufficient to contain certification paths
        // from a recognized "root" or "top-level certification authority" to
        // all of the sender certificates with which the set is associated.
        // However, there may be more certificates than necessary, or there MAY
        // be fewer than necessary.
        // <br/>
        // The precise meaning of a "certification path" is outside the scope of
        // this document. However, [PROFILE] provides a definition for X.509
        // certificates. Some applications may impose upper limits on the
        // length of a certification path; others may enforce certain
        // relationships between the subjects and issuers of certificates within
        // a certification path.
        // <br/>
        // Object tmp = signedData.getObjectAt(3);

        // the certificates
        X509CertParser x509CertParser = new X509CertParser();
        x509CertParser.engineInit(new ByteArrayInputStream(cmsData));
        Collection<Certificate> certificates;
        try {
            certificates = x509CertParser.engineReadAll();
        } catch (StreamParsingException e) {
            logger.log(Level.WARNING, "Error parsing certificate data: ", e);
            throw new SignatureIntegrityException("Error parsing certificate data ");
        }

        /**
         * SignerInfo ::= SEQUENCE {
         *    0, version CMSVersion,
         *    1, sid SignerIdentifier,
         *    2, digestAlgorithm DigestAlgorithmIdentifier,
         *    signedAttrs [0] IMPLICIT SignedAttributes OPTIONAL,
         *    signatureAlgorithm SignatureAlgorithmIdentifier,
         *    signature SignatureValue,
         *    unsignedAttrs [1] IMPLICIT UnsignedAttributes OPTIONAL }
         */
        // the signerInfos is going to be the last entry in the sequence.
        ASN1Set signerInfos = (ASN1Set) signedData.getObjectAt(signedData.size() - 1);
        // and we only need the first entry, as enveloped signatures aren't found in PDF land.
        ASN1Sequence signerInfo = (ASN1Sequence) signerInfos.getObjectAt(0);
        // If the SignerIdentifier is the CHOICE issuerAndSerialNumber, then the version MUST be 1.
        // If the SignerIdentifier is subjectKeyIdentifier, then the version MUST be 3.
        int signerVersion = ((ASN1Integer) signerInfo.getObjectAt(0)).getValue().intValue();
        /**
         * SignerIdentifier ::= CHOICE {
         *    issuerAndSerialNumber IssuerAndSerialNumber,
         *    subjectKeyIdentifier [0] SubjectKeyIdentifier }
         *
         *    SubjectKeyIdentifier ::= OCTET STRING
         */
        ASN1Sequence issuerAndSerialNumber = (ASN1Sequence) signerInfo.getObjectAt(1);
        signerCertificate = null;
        if (signerVersion == 1) {
            // parse out the issue and SerialNumber.
            X500Principal issuer;
            try {
                issuer = new X500Principal(issuerAndSerialNumber.getObjectAt(0).toASN1Primitive().getEncoded());
            } catch (IOException e1) {
                logger.warning("Could not create X500 Principle data ");
                throw new SignatureIntegrityException("Could not create X500 Principle data");
            }
            BigInteger serialNumber = ((ASN1Integer) issuerAndSerialNumber.getObjectAt(1)).getValue();
            signerCertificate = null;
            // signer cert should always be the first in the list.
            for (Object element : certificates) {
                X509Certificate certificate = (X509Certificate) element;
                if (certificate.getIssuerX500Principal().equals(issuer) &&
                        serialNumber.equals(certificate.getSerialNumber())) {
                    signerCertificate = certificate;
                    break;
                } else {
                    if (logger.isLoggable(Level.FINER)) {
                        logger.finer("Certificate and issuer could not be verified as the same entity.");
                    }
                }
            }
        } else if (signerVersion == 3) {
            // SubjectKeyIdentifier ::= OCTET STRING
            // ASN1Primitive subjectKeyIdentifier = issuerAndSerialNumber.getObjectAt(0).toASN1Primitive();
            throw new IllegalStateException("Singer version 3 not supported");
        }
        return signerInfo;
    }


    /**
     * The CMS associates a content type identifier with a content. The syntax MUST have ASN.1 type ContentInfo:
     * ContentInfo ::= SEQUENCE {
     * contentType ContentType,
     * content [0] EXPLICIT ANY DEFINED BY contentType }
     * ContentType ::= OBJECT IDENTIFIER
     */
    protected ASN1Sequence captureSignedData(byte[] cmsData)
            throws SignatureIntegrityException {
        ASN1Sequence cmsSequence = buildASN1Primitive(cmsData);
        if (cmsSequence == null || cmsSequence.getObjectAt(0) == null) {
            throw new SignatureIntegrityException("ContentInfo does not contain content type.");
        }
        /**
         * id-data OBJECT IDENTIFIER ::= { iso(1) member-body(2) us(840) rsadsi(113549) pkcs(1) pkcs7(7) 1 }
         * Currently not doing anything with this but we may need it at a later date to support different signed data.
         * But we are looking pkcs7 variants.
         */
        ASN1ObjectIdentifier objectIdentifier = (ASN1ObjectIdentifier) cmsSequence.getObjectAt(0);
        if (objectIdentifier == null ||
                !(ID_DATA_OBJECT_IDENTIFIER.equals(objectIdentifier.getId()) ||
                        ID_DIGESTED_DATA_OBJECT_IDENTIFIER.equals(objectIdentifier.getId()) ||
                        ID_ENCRYPTED_DATA_OBJECT_IDENTIFIER.equals(objectIdentifier.getId()) ||
                        ID_ENVELOPED_DATA_OBJECT_IDENTIFIER.equals(objectIdentifier.getId()) ||
                        ID_SIGNED_DATA_OBJECT_IDENTIFIER.equals(objectIdentifier.getId()))) {
            logger.warning("ANSI object id is not a valid PKCS7 identifier " + objectIdentifier);
            throw new SignatureIntegrityException("ANSI object id is not a valid PKCS7 identifier");
        } else {
            logger.finer("Object identifier: " + objectIdentifier.getId() + " " +
                    Pkcs7Validator.getObjectIdName(objectIdentifier.getId()));
        }

        if (!ID_SIGNED_DATA_OBJECT_IDENTIFIER.equals(objectIdentifier.getId())) {
            throw new SignatureIntegrityException("ANSI.1 object must be of type Signed Data");
        }
        // Signed-data content type -- start of parsing
        return (ASN1Sequence) ((ASN1TaggedObject) cmsSequence.getObjectAt(1)).getObject();
    }

    // Verify that the signature is indeed correct and verify the public key is a match.
    protected boolean verifySignedAttributes(String provider, X509Certificate signerCertificate,
                                             byte[] signatureValue,
                                             String signatureAlgorithmIdentifier,
                                             String digestAlgorithmIdentifier,
                                             byte[] attr) throws SignatureIntegrityException {
        try {
            Signature signature = createSignature(signerCertificate.getPublicKey(), provider,
                    signatureAlgorithmIdentifier, digestAlgorithmIdentifier);
            signature.update(attr);
            return signature.verify(signatureValue);
        } catch (InvalidKeyException e) {
            throw new SignatureIntegrityException(e);
        } catch (NoSuchAlgorithmException e) {
            throw new SignatureIntegrityException(e);
        } catch (SignatureException e) {
            throw new SignatureIntegrityException(e);
        }
    }

    // Creates a signature object for the given ID's and verifies the public key.
    protected Signature createSignature(PublicKey publicKey, String provider,
                                        String signatureAlgorithmIdentifier, String digestAlgorithmIdentifier)
            throws InvalidKeyException, NoSuchAlgorithmException {
        String encryptionAlgorithmName = AlgorithmIdentifier.getEncryptionAlgorithmName(signatureAlgorithmIdentifier);
        String digestAlgorithmName = AlgorithmIdentifier.getDigestAlgorithmName(digestAlgorithmIdentifier);
        String digestAlgorithm = digestAlgorithmName + ALGORITHM_WITH + encryptionAlgorithmName;
        logger.finer("DigestAlgorithm " + digestAlgorithm);
        Signature signature;
        if (provider != null) {
            try {
                signature = Signature.getInstance(digestAlgorithm, provider);
            } catch (NoSuchProviderException e) {
                signature = Signature.getInstance(digestAlgorithm);
            }
        } else {
            signature = Signature.getInstance(digestAlgorithm);
        }

        signature.initVerify(publicKey);
        return signature;
    }

    /**
     * Takes the DER-encoded PKCS#1 binary data or PKCS#7 binary data object and reads it into an
     * Abstract Syntax Notation One (ASNI.1) object.
     *
     * @return ASN1Sequence representing the Cryptographic Message Syntax (CMS), null if data stream
     * could not be loaded
     */
    protected ASN1Sequence buildASN1Primitive(byte[] cmsData) {
        try {
            // setup the
            ASN1InputStream abstractSyntaxNotationStream = new ASN1InputStream(new ByteArrayInputStream(cmsData));
            ASN1Primitive pkcs = abstractSyntaxNotationStream.readObject();

            if (pkcs instanceof ASN1Sequence) {
                if (logger.isLoggable(Level.FINER)) {
                    logger.finer("ASN1Sequence found starting sequence processing.  ");
                }
                return (ASN1Sequence) pkcs;
            } else if (logger.isLoggable(Level.FINER)) {
                logger.finer("ASN1Sequence was not found backing out.  ");
            }

        } catch (IOException e) {
            logger.log(Level.WARNING, "ASN1 stream could not be read.", e);
        }
        return null;
    }

    /**
     * Gets a descriptive name for the given ANSI.1 object identifier number.
     *
     * @param objectId object id to lookup against know list of PKCS#7 id's.
     * @return string describing the hard to read object id.
     */
    protected static String getObjectIdName(String objectId) {
        if (ID_DATA_OBJECT_IDENTIFIER.equals(objectId)) {
            return "ID Data Object Identifier";
        } else if (ID_SIGNED_DATA_OBJECT_IDENTIFIER.equals(objectId)) {
            return "ID Signed Data Object Identifier";
        } else if (ID_ENVELOPED_DATA_OBJECT_IDENTIFIER.equals(objectId)) {
            return "ID Enveloped Data Object Identifier";
        } else if (ID_ENCRYPTED_DATA_OBJECT_IDENTIFIER.equals(objectId)) {
            return "ID Encrypted Data Object Identifier";
        } else if (ID_DIGESTED_DATA_OBJECT_IDENTIFIER.equals(objectId)) {
            return "ID Digested Data Object Identifier";
        }
        return "Unknown";
    }

    /**
     * Validates the document against the data in the signatureDictionary.
     *
     * @throws SignatureIntegrityException
     */
    protected void validateDocument() throws SignatureIntegrityException {

        SignatureDictionary signatureDictionary = signatureFieldDictionary.getSignatureDictionary();

        Signature signature;
        MessageDigest messageDigestAlgorithm;
        MessageDigest eConMessageDigestAlgorithm;
        try {
            String provider = signatureDictionary.getFilter().getName();

            messageDigestAlgorithm = AlgorithmIdentifier.getDigestInstance(
                    digestAlgorithmIdentifier, provider);
            eConMessageDigestAlgorithm = AlgorithmIdentifier.getDigestInstance(
                    digestAlgorithmIdentifier, provider);

            signature = createSignature(signerCertificate.getPublicKey(), provider,
                    signatureAlgorithmIdentifier, digestAlgorithmIdentifier);

            PublicKey publicKey = signerCertificate.getPublicKey();
            if (logger.isLoggable(Level.FINER)) {
                logger.finer("Certificate: \n" + signerCertificate.toString());
                logger.finer("Public Key:  \n" + publicKey);
            }
        } catch (NoSuchProviderException e1) {
            logger.log(Level.WARNING, "No such provider found ", e1);
            return;
        } catch (NoSuchAlgorithmException e1) {
            logger.log(Level.WARNING, "No such algorithm found ", e1);
            return;
        } catch (InvalidKeyException e1) {
            logger.log(Level.WARNING, "Invalid key ", e1);
            return;
        }
        // let digest the data.
        ArrayList<Integer> byteRange = signatureFieldDictionary.getSignatureDictionary().getByteRange();
        SeekableInput documentInput = signatureFieldDictionary.getLibrary().getDocumentInput();
        documentInput.beginThreadAccess();
        try {
            documentInput.seekAbsolute(byteRange.get(0));
            byte[] firstSection = new byte[byteRange.get(1)];
            documentInput.read(firstSection);
            messageDigestAlgorithm.update(firstSection);
            documentInput.seekAbsolute(byteRange.get(2));
            byte[] secondSection = new byte[byteRange.get(3)];
            documentInput.read(secondSection);
            messageDigestAlgorithm.update(secondSection);
        } catch (IOException e) {
            throw new SignatureIntegrityException(e);
        } finally {
            documentInput.endThreadAccess();
        }
        // setup the compare
        try {
            // RFC3852 - The result of the message digest calculation process depends on whether the signedAttrs field
            // is present. When the field is absent, the result is just the message digest of the content as described
            // above. When the field is present, however, the result is the message digest of the complete DER encoding
            // of the SignedAttrs value contained in the signedAttrs field.
            byte[] documentDigestBytes = messageDigestAlgorithm.digest();
            if (signedAttributesSequence != null) {
                boolean encapsulatedDigestCheck = true;
                boolean verifyEncContentInfoData = true;
                if (encapsulatedContentInfoData != null) {
                    verifyEncContentInfoData = Arrays.equals(documentDigestBytes, encapsulatedContentInfoData);
                    eConMessageDigestAlgorithm.update(encapsulatedContentInfoData);
                    encapsulatedDigestCheck = Arrays.equals(eConMessageDigestAlgorithm.digest(), messageDigest);
                }
                boolean nonEncapsulatedDigestCheck = Arrays.equals(documentDigestBytes, messageDigest);
                // When the field is present, however, the result is the message digest of the complete DER encoding of
                // the SignedAttrs value contained in the signedAttrs field
                boolean signatureVerified =
                        verifySignedAttributes(signatureDictionary.getFilter().getName(), signerCertificate, signatureValue,
                                signatureAlgorithmIdentifier,
                                digestAlgorithmIdentifier,
                                signedAttributesSequence.getEncoded(ASN1Encoding.DER));
                if (logger.isLoggable(Level.FINER)) {
                    logger.finer("Encapsulated Digest verified: " + encapsulatedDigestCheck);
                    logger.finer("Non-encapsulated Digest verified: " + nonEncapsulatedDigestCheck);
                    logger.finer("Signature verified: " + signatureVerified);
                    logger.finer("Encapsulated data verified: " + verifyEncContentInfoData);
                }
                // verify the attributes.
                if ((encapsulatedDigestCheck || nonEncapsulatedDigestCheck) && signatureVerified && verifyEncContentInfoData) {
                    isDocumentModified = false;
                }
            } else {
                if (encapsulatedContentInfoData != null) {
                    signature.update(messageDigestAlgorithm.digest());
                }
                boolean nonEncapsulatedDigestCheck = Arrays.equals(documentDigestBytes, messageDigest);
                if (nonEncapsulatedDigestCheck) {
                    isDocumentModified = false;
                }
            }
        } catch (SignatureException e) {
            throw new SignatureIntegrityException(e);
        } catch (IOException e) {
            throw new SignatureIntegrityException(e);
        }
    }

    public boolean isDocumentModified() {
        return isDocumentModified;
    }

    public boolean isCertificateTrusted() {
        return isCertificateTrusted;
    }

    public boolean isSignerTimeValid() {
        return isSignerTimeValid;
    }

    public boolean isValidationTimeValid() {
        return isValidationTimeValid;
    }

    /**
     * Determined by the flags isDocumentModified && isCertificateTrusted && isSignerTimeValid && isValidationTimeValid;
     *
     * @return true if all flags are true, otherwise false.
     */
    public boolean isValid() {
        return !isDocumentModified && isCertificateTrusted && isSignerTimeValid && isValidationTimeValid;
    }
}
