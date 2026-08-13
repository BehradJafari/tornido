package io.tornado.config;

import jakarta.servlet.*;import jakarta.servlet.http.*;
import org.springframework.http.HttpHeaders;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.userdetails.*;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import java.io.IOException;import java.util.List;

@Component
public class JwtAuthenticationFilter extends OncePerRequestFilter {
    public static final String COOKIE_NAME="tornado_token";
    private final JwtService jwt;private final UserDetailsService users;
    public JwtAuthenticationFilter(JwtService jwt,UserDetailsService users){this.jwt=jwt;this.users=users;}
    @Override protected void doFilterInternal(HttpServletRequest request,HttpServletResponse response,FilterChain chain)throws ServletException,IOException{String token=bearer(request);if(token==null&&request.getCookies()!=null)for(Cookie cookie:request.getCookies())if(COOKIE_NAME.equals(cookie.getName())){token=cookie.getValue();break;}String username=token==null?null:jwt.verify(token);if(username!=null&&SecurityContextHolder.getContext().getAuthentication()==null)try{UserDetails user=users.loadUserByUsername(username);if(user.isEnabled())SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(user,null,user.getAuthorities()));}catch(UsernameNotFoundException ignored){}chain.doFilter(request,response);}
    private String bearer(HttpServletRequest request){String value=request.getHeader(HttpHeaders.AUTHORIZATION);return value!=null&&value.startsWith("Bearer ")?value.substring(7):null;}
}
