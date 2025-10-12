package com.limelight.nvstream.http;

import org.bouncycastle.crypto.BlockCipher;
import org.bouncycastle.crypto.engines.AESLightEngine;
import org.bouncycastle.crypto.params.KeyParameter;

import org.xmlpull.v1.XmlPullParserException;

import com.limelight.LimeLog;

import java.security.cert.Certificate;
import java.io.*;
import java.security.*;
import java.security.cert.*;
import java.util.Arrays;
import java.util.Locale;

public class PairingManager {

    private NvHTTP http;
    
    private PrivateKey pk;
    private X509Certificate cert;
    private byte[] pemCertBytes;

    private X509Certificate serverCert;
    
    public enum PairState {
        NOT_PAIRED,
        PAIRED,
        PIN_WRONG,
        FAILED,
        ALREADY_IN_PROGRESS
    }
    
    public PairingManager(NvHTTP http, LimelightCryptoProvider cryptoProvider) {
        this.http = http;
        this.cert = cryptoProvider.getClientCertificate();
        this.pemCertBytes = cryptoProvider.getPemEncodedClientCertificate();
        this.pk = cryptoProvider.getClientPrivateKey();
    }
    
    final private static char[] hexArray = "0123456789ABCDEF".toCharArray();
    private static String bytesToHex(byte[] bytes) {
        char[] hexChars = new char[bytes.length * 2];
        for ( int j = 0; j < bytes.length; j++ ) {
            int v = bytes[j] & 0xFF;
            hexChars[j * 2] = hexArray[v >>> 4];
            hexChars[j * 2 + 1] = hexArray[v & 0x0F];
        }
        return new String(hexChars);
    }
    
    private static byte[] hexToBytes(String s) {
        int len = s.length();
        if (len % 2 != 0) {
            throw new IllegalArgumentException("Illegal string length: "+len);
        }

        byte[] data = new byte[len / 2];
        for (int i = 0; i < len; i += 2) {
            data[i / 2] = (byte) ((Character.digit(s.charAt(i), 16) << 4)
                                 + Character.digit(s.charAt(i+1), 16));
        }
        return data;
    }
    
    private X509Certificate extractPlainCert(String text) throws XmlPullParserException, IOException
    {
        // Plaincert may be null if another client is already trying to pair
        String certText = NvHTTP.getXmlString(text, "plaincert", false);
        if (certText != null) {
            byte[] certBytes = hexToBytes(certText);

            try {
                CertificateFactory cf = CertificateFactory.getInstance("X.509");
                return (X509Certificate)cf.generateCertificate(new ByteArrayInputStream(certBytes));
            } catch (CertificateException e) {
                e.printStackTrace();
                throw new RuntimeException(e);
            }
        }
        else {
            return null;
        }
    }
    
    private byte[] generateRandomBytes(int length)
    {
        byte[] rand = new byte[length];
        new SecureRandom().nextBytes(rand);
        return rand;
    }
    
    private static byte[] saltPin(byte[] salt, String pin) throws UnsupportedEncodingException {
        byte[] saltedPin = new byte[salt.length + pin.length()];
        System.arraycopy(salt, 0, saltedPin, 0, salt.length);
        System.arraycopy(pin.getBytes("UTF-8"), 0, saltedPin, salt.length, pin.length());
        return saltedPin;
    }

    private static Signature getSha256SignatureInstanceForKey(Key key) throws NoSuchAlgorithmException {
        switch (key.getAlgorithm()) {
            case "RSA":
                return Signature.getInstance("SHA256withRSA");
            case "EC":
                return Signature.getInstance("SHA256withECDSA");
            default:
                throw new NoSuchAlgorithmException("Unhandled key algorithm: " + key.getAlgorithm());
        }
    }
    
    private static boolean verifySignature(byte[] data, byte[] signature, Certificate cert) {
        try {
            Signature sig = PairingManager.getSha256SignatureInstanceForKey(cert.getPublicKey());
            sig.initVerify(cert.getPublicKey());
            sig.update(data);
            return sig.verify(signature);
        } catch (NoSuchAlgorithmException | SignatureException | InvalidKeyException e) {
            e.printStackTrace();
            throw new RuntimeException(e);
        }
    }
    
    private static byte[] signData(byte[] data, PrivateKey key) {
        try {
            Signature sig = PairingManager.getSha256SignatureInstanceForKey(key);
            sig.initSign(key);
            sig.update(data);
            return sig.sign();
        } catch (NoSuchAlgorithmException | SignatureException | InvalidKeyException e) {
            e.printStackTrace();
            throw new RuntimeException(e);
        }
    }

    private static byte[] performBlockCipher(BlockCipher blockCipher, byte[] input) {
        int blockSize = blockCipher.getBlockSize();
        int blockRoundedSize = (input.length + (blockSize - 1)) & ~(blockSize - 1);

        byte[] blockRoundedInputData = Arrays.copyOf(input, blockRoundedSize);
        byte[] blockRoundedOutputData = new byte[blockRoundedSize];

        for (int offset = 0; offset < blockRoundedSize; offset += blockSize) {
            blockCipher.processBlock(blockRoundedInputData, offset, blockRoundedOutputData, offset);
        }

        return blockRoundedOutputData;
    }
    
    private static byte[] decryptAes(byte[] encryptedData, byte[] aesKey) {
        BlockCipher aesEngine = new AESLightEngine();
        aesEngine.init(false, new KeyParameter(aesKey));
        return performBlockCipher(aesEngine, encryptedData);
    }
    
    private static byte[] encryptAes(byte[] plaintextData, byte[] aesKey) {
        BlockCipher aesEngine = new AESLightEngine();
        aesEngine.init(true, new KeyParameter(aesKey));
        return performBlockCipher(aesEngine, plaintextData);
    }
    
    private static byte[] generateAesKey(PairingHashAlgorithm hashAlgo, byte[] keyData) {
        return Arrays.copyOf(hashAlgo.hashData(keyData), 16);
    }
    
    private static byte[] concatBytes(byte[] a, byte[] b) {
        byte[] c = new byte[a.length + b.length];
        System.arraycopy(a, 0, c, 0, a.length);
        System.arraycopy(b, 0, c, a.length, b.length);
        return c;
    }
    
    public static String generatePinString() {
        SecureRandom r = new SecureRandom();
        return String.format((Locale)null, "%d%d%d%d",
                r.nextInt(10), r.nextInt(10),
                r.nextInt(10), r.nextInt(10));
    }

    public X509Certificate getPairedCert() {
        return serverCert;
    }
    
    /**
     * 配对处理
     * @param serverInfo 服务端信息
     * @param pin 配对PIN码
     * @return PairResult 包含配对状态和pairname
     * @throws IOException
     * @throws XmlPullParserException
     */
    public PairResult pair(String serverInfo, String pin) throws IOException, XmlPullParserException, InterruptedException {
        PairingHashAlgorithm hashAlgo;

        int serverMajorVersion = http.getServerMajorVersion(serverInfo);
        LimeLog.info("Pairing with server generation: " + serverMajorVersion);
        if (serverMajorVersion >= 7) {
            // Gen 7+ uses SHA-256 hashing
            hashAlgo = new Sha256PairingHash();
        } else {
            // Prior to Gen 7, SHA-1 is used
            hashAlgo = new Sha1PairingHash();
        }

        // 生成用于PIN哈希的salt
        byte[] salt = generateRandomBytes(16);

        // 合并salt和pin，然后用它们生成AES密钥
        byte[] aesKey = generateAesKey(hashAlgo, saltPin(salt, pin));

        // 发送salt并获取服务端证书。此处没有读取超时，因为用户必须输入PIN后服务端才会响应
        String getCert = http.executePairingCommand("phrase=getservercert&salt=" +
                bytesToHex(salt) + "&clientcert=" + bytesToHex(pemCertBytes),
                false);
        if (!NvHTTP.getXmlString(getCert, "paired", true).equals("1")) {
            return new PairResult(PairState.FAILED, null);
        }

        // 获取配对名（pairname），兼容服务端未返回pairname的情况
        String pairName = NvHTTP.getXmlString(getCert, "pairname", false);

        // 保存证书以便后续检索
        serverCert = extractPlainCert(getCert);
        if (serverCert == null) {
            // 如果有其他设备正在配对，GFE会返回空证书
            http.unpair();
            return new PairResult(PairState.ALREADY_IN_PROGRESS, pairName);
        }

        // 要求此证书用于与此主机的TLS通信
        http.setServerCert(serverCert);

        // 生成随机挑战并用AES密钥加密
        byte[] randomChallenge = generateRandomBytes(16);
        byte[] encryptedChallenge = encryptAes(randomChallenge, aesKey);

        // 发送加密挑战到服务端
        String challengeResp = http.executePairingCommand("clientchallenge=" + bytesToHex(encryptedChallenge), true);
        if (!NvHTTP.getXmlString(challengeResp, "paired", true).equals("1")) {
            http.unpair();
            return new PairResult(PairState.FAILED, pairName);
        }

        // 解码服务端响应和后续挑战
        byte[] encServerChallengeResponse = hexToBytes(NvHTTP.getXmlString(challengeResp, "challengeresponse", true));
        byte[] decServerChallengeResponse = decryptAes(encServerChallengeResponse, aesKey);

        byte[] serverResponse = Arrays.copyOfRange(decServerChallengeResponse, 0, hashAlgo.getHashLength());
        byte[] serverChallenge = Arrays.copyOfRange(decServerChallengeResponse, hashAlgo.getHashLength(), hashAlgo.getHashLength() + 16);

        // 用另一个16字节的secret，结合secret、证书签名和challenge计算挑战响应哈希
        byte[] clientSecret = generateRandomBytes(16);
        byte[] challengeRespHash = hashAlgo.hashData(concatBytes(concatBytes(serverChallenge, cert.getSignature()), clientSecret));
        byte[] challengeRespEncrypted = encryptAes(challengeRespHash, aesKey);
        String secretResp = http.executePairingCommand("serverchallengeresp=" + bytesToHex(challengeRespEncrypted), true);
        if (!NvHTTP.getXmlString(secretResp, "paired", true).equals("1")) {
            http.unpair();
            return new PairResult(PairState.FAILED, pairName);
        }

        // 获取服务端签名的secret
        byte[] serverSecretResp = hexToBytes(NvHTTP.getXmlString(secretResp, "pairingsecret", true));
        byte[] serverSecret = Arrays.copyOfRange(serverSecretResp, 0, 16);
        byte[] serverSignature = Arrays.copyOfRange(serverSecretResp, 16, serverSecretResp.length);

        // 校验数据真实性
        if (!verifySignature(serverSecret, serverSignature, serverCert)) {
            // 取消配对流程
            http.unpair();
            // 可能是中间人攻击
            return new PairResult(PairState.FAILED, pairName);
        }

        // 校验服务端challenge是否与预期一致（即PIN是否正确）
        byte[] serverChallengeRespHash = hashAlgo.hashData(concatBytes(concatBytes(randomChallenge, serverCert.getSignature()), serverSecret));
        if (!Arrays.equals(serverChallengeRespHash, serverResponse)) {
            // 取消配对流程
            http.unpair();
            // 可能PIN错误
            return new PairResult(PairState.PIN_WRONG, pairName);
        }

        // 发送客户端签名的secret给服务端
        byte[] clientPairingSecret = concatBytes(clientSecret, signData(clientSecret, pk));
        String clientSecretResp = http.executePairingCommand("clientpairingsecret=" + bytesToHex(clientPairingSecret), true);
        if (!NvHTTP.getXmlString(clientSecretResp, "paired", true).equals("1")) {
            http.unpair();
            return new PairResult(PairState.FAILED, pairName);
        }

        // 执行初始挑战（似乎有必要让我们显示为已配对）
        String pairChallenge = http.executePairingChallenge();
        if (!NvHTTP.getXmlString(pairChallenge, "paired", true).equals("1")) {
            http.unpair();
            return new PairResult(PairState.FAILED, pairName);
        }

        return new PairResult(PairState.PAIRED, pairName);
    }

    /**
     * 配对结果类，包含配对状态和pairname
     */
    public static class PairResult {
        public final PairState state;
        public final String pairName;

        public PairResult(PairState state, String pairName) {
            this.state = state;
            this.pairName = pairName != null && !pairName.equals("unknown") ? pairName : "";
        }
    }
    
    private interface PairingHashAlgorithm {
        int getHashLength();
        byte[] hashData(byte[] data);
    }
    
    private static class Sha1PairingHash implements PairingHashAlgorithm {
        public int getHashLength() {
            return 20;
        }
        
        public byte[] hashData(byte[] data) {
            try {
                MessageDigest md = MessageDigest.getInstance("SHA-1");
                return md.digest(data);
            }
            catch (NoSuchAlgorithmException e) {
                e.printStackTrace();
                throw new RuntimeException(e);
            }
        }
    }
    
    private static class Sha256PairingHash implements PairingHashAlgorithm {
        public int getHashLength() {
            return 32;
        }
        
        public byte[] hashData(byte[] data) {
            try {
                MessageDigest md = MessageDigest.getInstance("SHA-256");
                return md.digest(data);
            }
            catch (NoSuchAlgorithmException e) {
                e.printStackTrace();
                throw new RuntimeException(e);
            }
        }
    }
}
