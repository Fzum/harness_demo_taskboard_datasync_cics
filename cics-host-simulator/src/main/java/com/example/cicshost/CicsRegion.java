package com.example.cicshost;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

/**
 * Whether the CICS region is up. While it is down (a simulated outage), every call under {@code
 * /cics} answers 503, the way a gateway answers when its region is not available.
 */
@Component
public class CicsRegion implements HandlerInterceptor {

  private static final Logger log = LoggerFactory.getLogger(CicsRegion.class);

  private final AtomicBoolean down = new AtomicBoolean();

  public boolean isDown() {
    return down.get();
  }

  public void setDown(boolean down) {
    if (this.down.getAndSet(down) != down) {
      log.info(down ? "CICS region is down (simulated outage)" : "CICS region is up again");
    }
  }

  @Override
  public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler)
      throws IOException {
    if (!down.get()) {
      return true;
    }
    response.setStatus(HttpStatus.SERVICE_UNAVAILABLE.value());
    response.setContentType(MediaType.TEXT_PLAIN_VALUE);
    response.getWriter().write("CICS region unavailable");
    return false;
  }
}
