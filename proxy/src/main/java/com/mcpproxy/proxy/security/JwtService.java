package com.mcpproxy.proxy.security;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.interfaces.RSAPrivateCrtKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.RSAPublicKeySpec;
import java.security.spec.X509EncodedKeySpec;
import java.util.Base64;
import java.util.Date;
import java.util.UUID;

/**
 * JWT 签发/验签服务（RS256，30 分钟有效期）。
 *
 * <p>功能：proxy 侧唯一的令牌服务——login/exchange 时<b>用私钥签发</b>用户访问 JWT，
 * JwtAuthFilter 验签时<b>用公钥校验</b>；同一份公钥分发到每台云手机的 mcp-server，
 * 云机即可独立验签 proxy 签发的令牌（密钥体系见 docs/security.md）。
 *
 * <p>开发思路：
 * <ul>
 *   <li>算法选 RS256 而非 HS256：云机只需公钥即可验签，私钥不出 proxy，
 *       单台云机被攻破也无法伪造令牌；</li>
 *   <li>密钥来源：环境变量注入（JWT_PRIVATE_KEY / JWT_PUBLIC_KEY，PKCS#8 + X.509，
 *       支持 PEM 全文或单行 Base64）；公钥缺省时从私钥（CRT 参数）推导；</li>
 *   <li>未配置私钥时生成临时开发密钥对并打 WARN——仅限本地开发，生产必须配置固定私钥；</li>
 *   <li>claims 设计：sub=uid、uid、instanceId、jti、exp=30min，instanceId 是单机隔离的关键。</li>
 * </ul>
 *
 * @author hubin
 * @since 2026-08-04（v1.3 由 HS256 改为 RS256：2026-08-05）
 */
@Service
public class JwtService {

    private static final Logger log = LoggerFactory.getLogger(JwtService.class);

    /** 访问 JWT 有效期：30 分钟（短时效降低被截获重放的窗口） */
    private static final long TTL_MILLIS = 30L * 60L * 1000L;

    /** 签发用私钥（仅 proxy 持有） */
    private final PrivateKey privateKey;
    /** 验签用公钥（与分发到云机的是同一份） */
    private final PublicKey publicKey;

    /**
     * 构造：加载 RSA 密钥材料。
     *
     * <p>伪代码：
     * <pre>
     *   私钥为空 -> 生成 RSA-2048 临时密钥对（dev 模式，WARN）
     *   否则     -> 解析 PKCS#8 私钥；公钥非空解析 X.509，空则从 RSAPrivateCrtKey 推导
     * </pre>
     *
     * @param privateKeyMaterial PEM 全文或单行 Base64 的 PKCS#8 私钥
     * @param publicKeyMaterial  PEM 全文或单行 Base64 的 X.509 公钥（可空）
     */
    public JwtService(@Value("${security.jwt.private-key:}") String privateKeyMaterial,
                      @Value("${security.jwt.public-key:}") String publicKeyMaterial) {
        try {
            if (privateKeyMaterial == null || privateKeyMaterial.isBlank()) {
                KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
                generator.initialize(2048);
                KeyPair keyPair = generator.generateKeyPair();
                this.privateKey = keyPair.getPrivate();
                this.publicKey = keyPair.getPublic();
                log.warn("security.jwt.private-key not configured, generated ephemeral dev keypair; "
                        + "cloud phone mcp-servers cannot verify tokens until the public key is distributed");
            } else {
                KeyFactory keyFactory = KeyFactory.getInstance("RSA");
                this.privateKey = keyFactory.generatePrivate(
                        new PKCS8EncodedKeySpec(decodeKeyMaterial(privateKeyMaterial)));
                if (publicKeyMaterial != null && !publicKeyMaterial.isBlank()) {
                    this.publicKey = keyFactory.generatePublic(
                            new X509EncodedKeySpec(decodeKeyMaterial(publicKeyMaterial)));
                } else {
                    // 公钥缺省：用私钥的 CRT 参数（modulus + publicExponent）重建公钥
                    RSAPrivateCrtKey crtKey = (RSAPrivateCrtKey) this.privateKey;
                    this.publicKey = keyFactory.generatePublic(
                            new RSAPublicKeySpec(crtKey.getModulus(), crtKey.getPublicExponent()));
                }
            }
        } catch (Exception e) {
            throw new IllegalStateException("failed to load RSA key material", e);
        }
    }

    /**
     * 签发用户访问 JWT。
     *
     * <p>伪代码：claims(sub=uid, uid, instanceId, jti=uuid, iat, exp=now+30min)，RS256 私钥签名。
     *
     * @param uid        用户 ID（登录时由临时 token 校验结果给出）
     * @param instanceId 绑定实例 ID（单机隔离的关键 claim）
     * @return compact JWT 字符串
     */
    public String issue(String uid, String instanceId) {
        long now = System.currentTimeMillis();
        return Jwts.builder()
                .subject(uid)
                .claim("uid", uid)
                .claim("instanceId", instanceId)
                .id(UUID.randomUUID().toString())
                .issuedAt(new Date(now))
                .expiration(new Date(now + TTL_MILLIS))
                .signWith(privateKey, Jwts.SIG.RS256)
                .compact();
    }

    /**
     * 验签并解析 JWT。
     *
     * <p>伪代码：公钥验签 + exp 检查，任一失败抛 JwtException（调用方按 401 处理）。
     *
     * @throws io.jsonwebtoken.JwtException 签名无效或已过期
     */
    public Claims parse(String token) {
        return Jwts.parser().verifyWith(publicKey).build().parseSignedClaims(token).getPayload();
    }

    /** 导出公钥（X.509 Base64），用于分发到云手机 mcp-server / 测试断言 */
    public String publicKeyBase64() {
        return Base64.getEncoder().encodeToString(publicKey.getEncoded());
    }

    public long expiresInSeconds() {
        return TTL_MILLIS / 1000L;
    }

    /** 密钥材料归一化：去 PEM 头尾与全部空白，得到纯 Base64 再解码 */
    private static byte[] decodeKeyMaterial(String material) {
        String cleaned = material
                .replaceAll("-----BEGIN [A-Z ]+-----", "")
                .replaceAll("-----END [A-Z ]+-----", "")
                .replaceAll("\\s", "");
        return Base64.getDecoder().decode(cleaned);
    }
}
