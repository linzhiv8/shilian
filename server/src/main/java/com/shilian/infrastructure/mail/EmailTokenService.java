package com.shilian.infrastructure.mail;

import com.shilian.domain.port.Clock;
import com.shilian.repo.entity.EmailTokenEntity;
import com.shilian.repo.mapper.EmailTokenMapper;
import com.shilian.util.RelativeTime;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.LocalDateTime;
import java.util.Base64;

/**
 * 邮箱令牌的签发与核销。
 *
 * <p><b>明文令牌只活在一个方法里。</b>
 * {@link #issue} 生成它、把哈希写进库、然后把它作为返回值交出去，
 * 之后它只存在于那封邮件里。别的地方（实体、日志、异常信息）都拿不到——
 * 实体上根本没有放明文的字段，这是刻意的，见 {@code EmailTokenEntity} 的注释。
 */
@Service
public class EmailTokenService {

    /** 找回密码。 */
    public static final String PURPOSE_RESET = "reset";
    /** 验证邮箱。 */
    public static final String PURPOSE_VERIFY = "verify";

    private static final SecureRandom RANDOM = new SecureRandom();
    private static final int TOKEN_BYTES = 32;
    private static final long VALID_MINUTES = 30;

    private final EmailTokenMapper tokens;
    private final Clock clock;

    public EmailTokenService(EmailTokenMapper tokens, Clock clock) {
        this.tokens = tokens;
        this.clock = clock;
    }

    /**
     * 签发一枚令牌，返回**明文**（唯一一次出现的地方，只该被拼进邮件链接）。
     *
     * <p><b>为什么用 32 字节随机数而不是 UUID。</b>
     * UUID v4 有 122 位随机性，其实够用；但它的字符形态是固定的，
     * 而这里要的是「不可猜」，直接用 CSPRNG 拿满 256 位更直白，
     * 也免得有人以后换成 UUID v1（那个带时间戳和 MAC，是可预测的）。
     */
    public String issue(String userId, String email, String purpose) {
        byte[] raw = new byte[TOKEN_BYTES];
        RANDOM.nextBytes(raw);
        String token = Base64.getUrlEncoder().withoutPadding().encodeToString(raw);

        LocalDateTime now = clock.now();
        EmailTokenEntity e = new EmailTokenEntity();
        e.setUserId(userId);
        e.setEmail(email);
        e.setPurpose(purpose);
        e.setTokenHash(sha256(token));
        e.setExpiresAt(RelativeTime.format(now.plusMinutes(VALID_MINUTES)));
        e.setCreatedAt(RelativeTime.format(now));
        tokens.insert(e);
        return token;
    }

    /**
     * 核销一枚令牌。返回它所属的记录，不可用就返回 null。
     *
     * <p><b>「标记已用」必须在这里一次做完，不能留给调用方。</b>
     * 分成「查一次」和「标记一次」两步的话，并发点两下链接会两次都查到可用，
     * 然后两次都去改密码。现在 {@code markUsed} 的 WHERE 里带
     * {@code used_at IS NULL}，返回 0 就说明这一瞬间被别人抢先了，直接判不可用。
     */
    public EmailTokenEntity consume(String rawToken, String purpose) {
        if (rawToken == null || rawToken.isBlank()) {
            return null;
        }
        String now = RelativeTime.format(clock.now());
        EmailTokenEntity found = tokens.findUsable(sha256(rawToken), purpose, now);
        if (found == null) {
            return null;
        }
        int rows = tokens.markUsed(found.getId(), now);
        return rows == 0 ? null : found;
    }

    /** 作废某个用户某一用途下所有还没用的令牌。改完密码之后要调一次，理由见 Mapper。 */
    public void invalidateAll(String userId, String purpose) {
        tokens.invalidateAll(userId, purpose, RelativeTime.format(clock.now()));
    }

    /**
     * SHA-256 的十六进制。
     *
     * <p><b>为什么是裸 SHA-256 而不是 bcrypt。</b>
     * bcrypt 慢是它的价值所在，但那个价值只在「原文空间小、能被穷举」时才成立
     * （密码就是这种）。这里是 256 位随机令牌，穷举不可行，
     * 慢哈希带来的只有每次校验几十毫秒的无谓开销。
     * 换个角度说：令牌已经足够不可猜，就不需要用慢哈希去补。
     */
    private static String sha256(String s) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] out = md.digest(s.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(out.length * 2);
            for (byte b : out) {
                sb.append(Character.forDigit((b >> 4) & 0xF, 16));
                sb.append(Character.forDigit(b & 0xF, 16));
            }
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            // SHA-256 是 JDK 强制实现的算法，到不了这里
            throw new IllegalStateException("SHA-256 不可用", e);
        }
    }
}
