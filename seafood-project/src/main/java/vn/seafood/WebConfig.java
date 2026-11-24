package vn.seafood;

import jakarta.servlet.http.*;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.config.annotation.*;

@Configuration
public class WebConfig implements WebMvcConfigurer {
  public void addInterceptors(InterceptorRegistry r) {
    r.addInterceptor(
            new HandlerInterceptor() {
              public boolean preHandle(HttpServletRequest q, HttpServletResponse p, Object h)
                  throws Exception {
                p.setHeader("X-Content-Type-Options", "nosniff");
                p.setHeader("X-Frame-Options", "DENY");
                p.setHeader("Cache-Control", "no-store");
                if (!q.getMethod().equals("GET") && !q.getMethod().equals("HEAD")) {
                  HttpSession s = q.getSession(false);
                  String csrf = q.getHeader("X-CSRF-Token");
                  if (s == null || csrf == null || !csrf.equals(s.getAttribute("csrf"))) {
                    p.setStatus(403);
                    p.setContentType("application/json;charset=UTF-8");
                    p.getWriter()
                        .write("{\"error\":\"Phiên hết hạn. Tải lại trang rồi thử lại.\"}");
                    return false;
                  }
                  String origin = q.getHeader("Origin");
                  if (origin != null
                      && !origin.equals(q.getScheme() + "://" + q.getHeader("Host"))) {
                    p.sendError(403);
                    return false;
                  }
                }
                return true;
              }
            })
        .addPathPatterns("/api/**");
  }
}
