package org.tron.core.services.http;

import lombok.extern.slf4j.Slf4j;
import org.junit.*;

import org.tron.common.application.TronApplicationContext;

import org.tron.common.utils.FileUtil;
import org.tron.core.Constant;
import org.tron.core.Wallet;

import org.tron.core.config.DefaultConfig;
import org.tron.core.config.args.Args;
import org.tron.core.db.Manager;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.*;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@Slf4j
public class ListNodesServletTest {
  private static String dbPath = "service-test";
  private static TronApplicationContext context;
  private ListNodesServlet listNodesServlet;
  private HttpServletRequest request;
  private HttpServletResponse response;

  static {
    Args.setParam(new String[] {"--output-directory", dbPath}, Constant.TEST_CONF);
    // 启服务，具体的端口号啥的在DefaultConfig.class里写死的
    context = new TronApplicationContext(DefaultConfig.class);
  }

  @AfterClass
  public static void removeDb() {
    Args.clearParam();
    context.destroy();
    if (FileUtil.deleteDir(new File(dbPath))) {
      logger.info("Release resources successful.");
    } else {
      logger.info("Release resources failure.");
    }
  }

  /** Init. */
  @Before
  public void setUp() throws InterruptedException {
    listNodesServlet = context.getBean(ListNodesServlet.class);
    this.request = mock(HttpServletRequest.class);
    this.response = mock(HttpServletResponse.class);
  }

  @After
  public void tearDown() {
    if (FileUtil.deleteDir(new File("temp.txt"))) {
      logger.info("Release resources successful.");
    } else {
      logger.info("Release resources failure.");
    }
  }

  @Test
  public void testDoGet() {
    String result = "";
    try {
      PrintWriter writer = new PrintWriter("temp.txt");
      when(response.getWriter()).thenReturn(writer);
      listNodesServlet.doGet(request, response);
      writer.close();
      FileInputStream fileInputStream = new FileInputStream("temp.txt");
      InputStreamReader inputStreamReader = new InputStreamReader(fileInputStream);
      BufferedReader bufferedReader = new BufferedReader(inputStreamReader);

      StringBuffer sb = new StringBuffer();
      String text = null;
      while ((text = bufferedReader.readLine()) != null) {
        sb.append(text);
      }
      fileInputStream.close();
      inputStreamReader.close();
      bufferedReader.close();
      Assert.assertTrue(sb.toString().contains("{}"));
    } catch (Exception e) {
      Assert.fail();
    }
  }

  @Test
  public void testDoPost() {
    String result = "";
    try {
      PrintWriter writer = new PrintWriter("temp.txt");
      when(response.getWriter()).thenReturn(writer);
      listNodesServlet.doPost(request, response);
      writer.close();
      FileInputStream fileInputStream = new FileInputStream("temp.txt");
      InputStreamReader inputStreamReader = new InputStreamReader(fileInputStream);
      BufferedReader bufferedReader = new BufferedReader(inputStreamReader);

      StringBuffer sb = new StringBuffer();
      String text = null;
      while ((text = bufferedReader.readLine()) != null) {
        sb.append(text);
      }
      fileInputStream.close();
      inputStreamReader.close();
      bufferedReader.close();
      Assert.assertTrue(sb.toString().contains("{}"));
    } catch (Exception e) {
      Assert.fail();
    }
  }
}
