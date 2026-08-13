package io.tornado.config;

import com.auth0.jwt.JWT;
import com.auth0.jwt.algorithms.Algorithm;
import com.auth0.jwt.exceptions.JWTVerificationException;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;
import java.time.Instant;

@Service
public class JwtService {
    private static final String ISSUER="tornado";
    private final AuthProperties props;private final Algorithm algorithm;
    public JwtService(AuthProperties props){this.props=props;algorithm=Algorithm.HMAC256(props.jwtSecret());}
    public String issue(Authentication auth){Instant now=Instant.now();return JWT.create().withIssuer(ISSUER).withSubject(auth.getName()).withIssuedAt(now).withExpiresAt(now.plus(props.tokenTtl())).sign(algorithm);}
    public String verify(String token){try{return JWT.require(algorithm).withIssuer(ISSUER).build().verify(token).getSubject();}catch(JWTVerificationException e){return null;}}
    public long expiresInSeconds(){return props.tokenTtl().toSeconds();}
}
