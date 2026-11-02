package jp.numajo.fgroup.shift.util;

import java.security.SecureRandom;

import org.springframework.stereotype.Component;

/**
 * 初期パスワードを作る（見まちがえやすい I・O・l・0・1 を使わない）。
 */
@Component
public class PasswordGenerator {

    /** 長さ */
    public static final int LENGTH = 10;
    /** 使う文字 */
    static final String CHARS = "ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz23456789";

    private final SecureRandom random = new SecureRandom();

    /** 英字と数字を両方1文字以上含む10文字を返す。 */
    public String generate() {
        while (true) {
            StringBuilder sb = new StringBuilder(LENGTH);
            for (int i = 0; i < LENGTH; i++) {
                sb.append(CHARS.charAt(random.nextInt(CHARS.length())));
            }
            String s = sb.toString();
            if (isValid(s)) {
                return s;
            }
        }
    }

    /** 英字と数字を両方含むか。 */
    static boolean isValid(String s) {
        boolean letter = false;
        boolean digit = false;
        for (char c : s.toCharArray()) {
            if (Character.isDigit(c)) {
                digit = true;
            } else if (Character.isLetter(c)) {
                letter = true;
            }
        }
        return letter && digit;
    }
}
