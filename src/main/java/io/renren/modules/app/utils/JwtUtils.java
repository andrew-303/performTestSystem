package io.renren.modules.app.utils;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.SignatureAlgorithm;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.Date;

/**
 * jwt工具类
 */
@ConfigurationProperties(prefix = "renren.jwt")
@Component
public class JwtUtils {
    private Logger logger = LoggerFactory.getLogger(getClass());

    private String secret;
    private long expire;
    private String header;

    /**
     * 生成jwt token
     */
    public String generateToken(long userId) {
        Date nowDate = new Date();
        //过期时间
        Date expireDate = new Date(nowDate.getTime() + expire * 1000);

        //下面就是在为payload添加各种标准声明和私有声明了
        return Jwts.builder()   //new 一个JwtBuilder，设置jwt的body
                .setHeaderParam("type","JWT")   //头部用于描述关于该JWT的最基本的信息，例如其类型以及签名所用的算法等
                .setSubject(userId+"")  //sub(Subject)：代表这个JWT的主体，即它的所有人，这个是一个json格式的字符串，可以存放什么userid，roldid之类的，作为什么用户的唯一标志。
                .setIssuedAt(nowDate)   //iat: jwt的签发时间
                .setExpiration(expireDate)  //什么时候过期，这里是一个Unix时间戳
                .signWith(SignatureAlgorithm.HS512,secret)//设置签名使用的签名算法和签名使用的秘钥
                .compact();//就开始压缩为xxxxxx.xxxxxxx.xxxxxx这样的jwt

    }

    /**
     * 解密jwt
     */
    public Claims getClaimByToken(String token) {
        try {
            return Jwts.parser()            //得到DefaultJwtParser
                    .setSigningKey(secret)  //设置签名的秘钥
                    .parseClaimsJws(token)  //设置需要解析的jwt
                    .getBody();
        } catch (Exception e) {
            logger.debug("validate is token error ",e);
            return null;
        }
    }

    /**
     * token是否过期
     * @param expiration
     * @return true：过期
     */
    public boolean isTokenExpired(Date expiration) {
        return expiration.before(new Date());
    }

    public String getSecret() {
        return secret;
    }

    public void setSecret(String secret) {
        this.secret = secret;
    }

    public long getExpire() {
        return expire;
    }

    public void setExpire(long expire) {
        this.expire = expire;
    }

    public String getHeader() {
        return header;
    }

    public void setHeader(String header) {
        this.header = header;
    }
}
