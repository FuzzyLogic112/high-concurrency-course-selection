package com.ncuky.cs.security;

import com.ncuky.cs.config.AppProps;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.springframework.stereotype.Component;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.util.Date;

@Component
public class JwtUtil {

    private final SecretKey key;
    private final long expireMillis;

    public JwtUtil(AppProps props) {
        byte[] raw = props.getJwt().getSecret().getBytes(StandardCharsets.UTF_8);
        if (raw.length < 32) {
            throw new IllegalStateException(
                    "app.jwt.secret 至少要 32 字节，当前 " + raw.length + " 字节。HS256 签名密钥不能太短。");
        }
        this.key = Keys.hmacShaKeyFor(raw);
        this.expireMillis = props.getJwt().getExpireMinutes() * 60_000L;
    }

    public String issue(Long userId, Integer role, Long studentId, String username) {
        long now = System.currentTimeMillis();
        return Jwts.builder()
                .subject(String.valueOf(userId))
                .claim("role", role)
                .claim("sid", studentId)
                .claim("uname", username)
                .issuedAt(new Date(now))
                .expiration(new Date(now + expireMillis))
                .signWith(key)
                .compact();
    }

    /** 解析失败（过期、签名不对、格式非法）统一返回 null，由调用方转成 401 */
    public UserContext.Principal parse(String token) {
        try {
            Claims c = Jwts.parser().verifyWith(key).build()
                    .parseSignedClaims(token).getPayload();
            Long userId = Long.valueOf(c.getSubject());
            Integer role = c.get("role", Integer.class);
            Number sid = c.get("sid", Number.class);
            String uname = c.get("uname", String.class);
            return new UserContext.Principal(userId, role,
                    sid == null ? null : sid.longValue(), uname);
        } catch (JwtException | IllegalArgumentException e) {
            return null;
        }
    }
}
