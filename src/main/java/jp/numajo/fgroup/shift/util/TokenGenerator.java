package jp.numajo.fgroup.shift.util;

import java.security.SecureRandom;
import java.util.Base64;

import org.springframework.stereotype.Component;

/**
 * カレンダー購読URLのトークンを作る（32バイトの乱数を Base64URL にした43文字）。
 */
@Component
public class TokenGenerator {

    /** 乱数のバイト数 */
    static final int BYTES = 32;

    private final SecureRandom random = new SecureRandom();

    public String generate() {
        byte[] b = new byte[BYTES];
        random.nextBytes(b);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(b);
    }
}
