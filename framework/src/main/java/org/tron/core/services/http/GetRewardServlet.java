package org.tron.core.services.http;

import java.io.IOException;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.bouncycastle.util.encoders.DecoderException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.tron.core.db.Manager;


@Component
@Slf4j(topic = "API")
public class GetRewardServlet extends RateLimiterServlet {

  @Autowired
  private Manager manager;

  protected void doGet(HttpServletRequest request, HttpServletResponse response) {
    long value = 0;
    try {
      byte[] address = Util.getAddress(request);
      if (address != null) {
        value = manager.getMortgageService().queryReward(address);
      }
      response.getWriter().println("{\"reward\": " + value + "}");
    } catch (DecoderException | IllegalArgumentException e) {
      try {
        response.getWriter()
            .println("{\"Error\": " + "\"INVALID address, " + e.getMessage() + "\"}");
      } catch (IOException ioe) {
        logger.debug("IOException: {}", ioe.getMessage());
      }
    } catch (Exception e) {
      logger.error("", e);
      try {
        if ("STREAMED".equals(e.getMessage())) {
          response.getWriter().println("{\"reward\": " + value + "}");
        } else {
          response.getWriter().println(Util.printErrorMsg(e));
        }
      } catch (IOException ioe) {
        logger.debug("IOException: {}", ioe.getMessage());
      }
    }
  }

  protected void doPost(HttpServletRequest request, HttpServletResponse response) {
    doGet(request, response);
  }
}
