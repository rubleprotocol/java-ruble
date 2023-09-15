package org.tron.common.config.args;

import java.io.IOException;
import org.junit.After;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.tron.common.parameter.RateLimiterInitialization;
import org.tron.core.Constant;
import org.tron.core.config.args.Args;


public class ArgsTest {

  @Rule
  public final TemporaryFolder temporaryFolder = new TemporaryFolder();

  @Before
  public void init() throws IOException {
    Args.setParam(new String[] {"--output-directory",
        temporaryFolder.newFolder().toString(), "--p2p-disable", "true",
        "--debug"}, Constant.TEST_CONF);
  }

  @After
  public void destroy() {
    Args.clearParam();
  }

  @Test
  public void testConfig() {
    Assert.assertEquals(Args.getInstance().getMaxTransactionPendingSize(), 2000);
    Assert.assertEquals(Args.getInstance().getPendingTransactionTimeout(), 60_000);
    Assert.assertEquals(Args.getInstance().getMaxFastForwardNum(), 3);
    Assert.assertEquals(Args.getInstance().getBlockCacheTimeout(), 60);
    Assert.assertEquals(Args.getInstance().isNodeDetectEnable(), false);
    Assert.assertFalse(Args.getInstance().isNodeEffectiveCheckEnable());
    Assert.assertEquals(Args.getInstance().getRateLimiterGlobalQps(), 1000);
    Assert.assertEquals(Args.getInstance().getRateLimiterGlobalIpQps(), 1000);
    Assert.assertEquals(Args.getInstance().p2pDisable, true);
    Assert.assertEquals(Args.getInstance().getMaxTps(), 1000);
    RateLimiterInitialization rateLimiter = Args.getInstance().getRateLimiterInitialization();
    Assert.assertEquals(rateLimiter.getHttpMap().size(), 1);
    Assert.assertEquals(rateLimiter.getRpcMap().size(), 0);
  }
}
