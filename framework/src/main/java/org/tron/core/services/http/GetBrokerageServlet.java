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
public class GetBrokerageServlet extends RateLimiterServlet {

  @Autowired
  private Manager manager;

  protected void doGet(HttpServletRequest request, HttpServletResponse response) {
    int value = 0;
    try {
      byte[] address = Util.getAddress(request);
      long cycle = manager.getDynamicPropertiesStore().getCurrentCycleNumber();
      if (address != null) {
        value = manager.getDelegationStore().getBrokerage(cycle, address);
      }
      response.getWriter().println("{\"brokerage\": " + value + "}");
    } catch (DecoderException | IllegalArgumentException e) {
      try {
        response.getWriter()
            .println("{\"Error\": " + "\"INVALID address, " + e.getMessage() + "\"}");
      } catch (IOException ioe) {
        logger.debug("IOException: {}", ioe.getMessage());
      }
    } catch (Exception e) {
      if (e.getMessage().equals("STREAMED")) {
        try {
          response.getWriter().println("{\"reward\": " + value + "}");
        } catch (IOException ioe) {
          logger.debug("IOException: {}", ioe.getMessage());
        }
      } else {
        Util.processError(e, response);
      }
    }
  }

  protected void doPost(HttpServletRequest request, HttpServletResponse response) {
    doGet(request, response);
  }
}
