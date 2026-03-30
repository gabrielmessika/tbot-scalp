package com.tbot.scalp.service;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.math.BigInteger;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.msgpack.core.MessagePack;
import org.msgpack.core.MessagePacker;
import org.web3j.crypto.ECKeyPair;
import org.web3j.crypto.Hash;
import org.web3j.crypto.Keys;
import org.web3j.crypto.Sign;

import lombok.Getter;
import lombok.extern.slf4j.Slf4j;

/**
 * Handles Ethereum EIP-712 signing for Hyperliquid exchange API.
 * <p>
 * Hyperliquid uses a "phantom agent" scheme:
 * <ul>
 * <li>Domain: {name: "Exchange", version: "1", chainId: 1337, verifyingContract: 0x0}</li>
 * <li>Type: Agent(string source, bytes32 connectionId)</li>
 * <li>source = "a" (mainnet) or "b" (testnet)</li>
 * <li>connectionId = keccak256(msgpack(action) + nonce_8BE + vault_flag)</li>
 * </ul>
 */
@Slf4j
public class HyperliquidSigner {

    private static final long CHAIN_ID = 1337L; // Hyperliquid L1

    /** EIP-712 domain separator, precomputed. */
    private final byte[] domainSeparator;

    private final ECKeyPair keyPair;

    @Getter
    private final String address;

    private final boolean isMainnet;

    /**
     * @param privateKeyHex hex-encoded private key (with or without 0x prefix)
     */
    public HyperliquidSigner(String privateKeyHex) {
        this(privateKeyHex, true);
    }

    public HyperliquidSigner(String privateKeyHex, boolean isMainnet) {
        String cleanKey = privateKeyHex.startsWith("0x")
                ? privateKeyHex.substring(2)
                : privateKeyHex;
        this.keyPair = ECKeyPair.create(new BigInteger(cleanKey, 16));
        this.address = "0x" + Keys.getAddress(keyPair);
        this.isMainnet = isMainnet;
        this.domainSeparator = computeDomainSeparator();
        log.info("[HYPERLIQUID] Signer initialized for wallet: {}", maskAddress(address));
    }

    /**
     * Build the full signed payload for the /exchange endpoint.
     *
     * @param action the action map (e.g. order, cancel, updateLeverage)
     * @param nonce  timestamp in milliseconds
     * @return the complete request body with action, nonce, signature
     */
    public Map<String, Object> buildSignedRequest(Map<String, Object> action, long nonce) {
        // 1. Compute connectionId = keccak256(msgpack(action) + nonce_8BE + vault_flag)
        byte[] connectionId = computeConnectionId(action, nonce, null);

        // 2. Compute EIP-712 struct hash for Agent(string source, bytes32 connectionId)
        byte[] structHash = computeAgentStructHash(connectionId);

        // 3. EIP-712 digest: keccak256(0x1901 + domainSeparator + structHash)
        byte[] digest = eip712Digest(structHash);

        // 4. Sign
        Sign.SignatureData sig = Sign.signMessage(digest, keyPair, false);

        Map<String, Object> signature = new LinkedHashMap<>();
        signature.put("r", "0x" + bytesToHex(sig.getR()));
        signature.put("s", "0x" + bytesToHex(sig.getS()));
        signature.put("v", Byte.toUnsignedInt(sig.getV()[0]));

        Map<String, Object> request = new LinkedHashMap<>();
        request.put("action", action);
        request.put("nonce", nonce);
        request.put("signature", signature);
        return request;
    }

    // ==================== EIP-712 Internals ====================

    /**
     * Compute the EIP-712 domain separator for Hyperliquid.
     * Domain: {name: "Exchange", version: "1", chainId: 1337, verifyingContract: 0x0}
     */
    private byte[] computeDomainSeparator() {
        byte[] typeHash = keccak256(
                "EIP712Domain(string name,string version,uint256 chainId,address verifyingContract)"
                        .getBytes(StandardCharsets.UTF_8));
        byte[] nameHash = keccak256("Exchange".getBytes(StandardCharsets.UTF_8));
        byte[] versionHash = keccak256("1".getBytes(StandardCharsets.UTF_8));

        // ABI encode: typeHash + nameHash + versionHash + chainId + verifyingContract(0x0)
        ByteBuffer buf = ByteBuffer.allocate(5 * 32);
        buf.put(padLeft(typeHash, 32));
        buf.put(padLeft(nameHash, 32));
        buf.put(padLeft(versionHash, 32));
        buf.put(padLeft(bigIntToBytes(BigInteger.valueOf(CHAIN_ID)), 32));
        buf.put(padLeft(new byte[20], 32)); // verifyingContract = address(0)

        return keccak256(buf.array());
    }

    /**
     * Compute the struct hash for Agent(string source, bytes32 connectionId).
     * <p>
     * EIP-712 encoding:
     * - string source → keccak256("a") for mainnet, keccak256("b") for testnet
     * - bytes32 connectionId → used directly (already 32 bytes)
     */
    private byte[] computeAgentStructHash(byte[] connectionId) {
        byte[] typeHash = keccak256(
                "Agent(string source,bytes32 connectionId)"
                        .getBytes(StandardCharsets.UTF_8));

        // EIP-712: string values are encoded as keccak256(string)
        String source = isMainnet ? "a" : "b";
        byte[] sourceHash = keccak256(source.getBytes(StandardCharsets.UTF_8));

        ByteBuffer buf = ByteBuffer.allocate(3 * 32);
        buf.put(padLeft(typeHash, 32));
        buf.put(padLeft(sourceHash, 32));
        buf.put(padLeft(connectionId, 32)); // already 32 bytes from keccak256

        return keccak256(buf.array());
    }

    /**
     * Compute the connectionId for the phantom agent:
     * keccak256(msgpack(action) + nonce_8bytes_BE + vault_flag)
     *
     * @param action       the action map
     * @param nonce        timestamp in milliseconds
     * @param vaultAddress null if no vault, otherwise hex address
     * @return 32-byte keccak256 hash
     */
    byte[] computeConnectionId(Map<String, Object> action, long nonce, String vaultAddress) {
        try {
            ByteArrayOutputStream baos = new ByteArrayOutputStream();

            // 1. msgpack encode the action
            byte[] msgpackBytes = msgpackEncode(action);
            baos.write(msgpackBytes);

            // 2. Append nonce as 8 bytes big-endian
            ByteBuffer nonceBuf = ByteBuffer.allocate(8);
            nonceBuf.putLong(nonce);
            baos.write(nonceBuf.array());

            // 3. Append vault flag
            if (vaultAddress == null) {
                baos.write(0x00);
            } else {
                baos.write(0x01);
                baos.write(hexToBytes(vaultAddress.startsWith("0x")
                        ? vaultAddress.substring(2) : vaultAddress));
            }

            return keccak256(baos.toByteArray());
        } catch (IOException e) {
            throw new RuntimeException("Failed to compute connection ID", e);
        }
    }

    /**
     * Compute the final EIP-712 digest: keccak256(0x1901 + domainSeparator + structHash)
     */
    private byte[] eip712Digest(byte[] structHash) {
        ByteBuffer buf = ByteBuffer.allocate(2 + 32 + 32);
        buf.put((byte) 0x19);
        buf.put((byte) 0x01);
        buf.put(domainSeparator);
        buf.put(structHash);
        return keccak256(buf.array());
    }

    // ==================== Msgpack Encoding ====================

    /**
     * Encode a value using msgpack, matching the Hyperliquid Python SDK behavior.
     * <p>
     * Key ordering in maps is preserved (uses LinkedHashMap iteration order).
     */
    @SuppressWarnings("unchecked")
    byte[] msgpackEncode(Object obj) throws IOException {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        try (MessagePacker packer = MessagePack.newDefaultPacker(baos)) {
            packValue(packer, obj);
        }
        return baos.toByteArray();
    }

    @SuppressWarnings("unchecked")
    private void packValue(MessagePacker packer, Object obj) throws IOException {
        if (obj == null) {
            packer.packNil();
        } else if (obj instanceof Map) {
            Map<String, Object> map = (Map<String, Object>) obj;
            packer.packMapHeader(map.size());
            for (var entry : map.entrySet()) {
                packer.packString(entry.getKey());
                packValue(packer, entry.getValue());
            }
        } else if (obj instanceof List) {
            List<?> list = (List<?>) obj;
            packer.packArrayHeader(list.size());
            for (Object item : list) {
                packValue(packer, item);
            }
        } else if (obj instanceof String s) {
            packer.packString(s);
        } else if (obj instanceof Boolean b) {
            packer.packBoolean(b);
        } else if (obj instanceof Integer i) {
            packer.packInt(i);
        } else if (obj instanceof Long l) {
            packer.packLong(l);
        } else if (obj instanceof Double d) {
            // Hyperliquid Python SDK uses msgpack default for floats
            packer.packDouble(d);
        } else if (obj instanceof Float f) {
            packer.packFloat(f);
        } else {
            // Fallback: convert to string
            packer.packString(String.valueOf(obj));
        }
    }

    // ==================== Utilities ====================

    private static byte[] keccak256(byte[] input) {
        return Hash.sha3(input);
    }

    private static byte[] padLeft(byte[] data, int length) {
        if (data.length >= length) {
            byte[] result = new byte[length];
            System.arraycopy(data, data.length - length, result, 0, length);
            return result;
        }
        byte[] result = new byte[length];
        System.arraycopy(data, 0, result, length - data.length, data.length);
        return result;
    }

    private static byte[] bigIntToBytes(BigInteger value) {
        byte[] bytes = value.toByteArray();
        if (bytes.length > 1 && bytes[0] == 0) {
            byte[] trimmed = new byte[bytes.length - 1];
            System.arraycopy(bytes, 1, trimmed, 0, trimmed.length);
            return trimmed;
        }
        return bytes;
    }

    static String bytesToHex(byte[] bytes) {
        StringBuilder sb = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) {
            sb.append(String.format("%02x", b));
        }
        return sb.toString();
    }

    private static byte[] hexToBytes(String hex) {
        int len = hex.length();
        byte[] data = new byte[len / 2];
        for (int i = 0; i < len; i += 2) {
            data[i / 2] = (byte) ((Character.digit(hex.charAt(i), 16) << 4)
                    + Character.digit(hex.charAt(i + 1), 16));
        }
        return data;
    }

    private String maskAddress(String addr) {
        if (addr == null || addr.length() < 10) return "***";
        return addr.substring(0, 6) + "..." + addr.substring(addr.length() - 4);
    }
}
