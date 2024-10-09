package org.tron.common.application;

import java.util.concurrent.CountDownLatch;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.tron.common.parameter.CommonParameter;
import org.tron.core.ChainBaseManager;
import org.tron.core.config.args.Args;
import org.tron.core.consensus.ConsensusService;
import org.tron.core.db.Manager;
import org.tron.core.metrics.MetricsUtil;
import org.tron.core.net.TronNetService;

@Slf4j(topic = "app")
@Component
public class ApplicationImpl implements Application {

  @Autowired
  private ServiceContainer services;

  @Autowired
  private TronNetService tronNetService;

  @Autowired
  private Manager dbManager;

  @Autowired
  private ChainBaseManager chainBaseManager;

  @Autowired
  private ConsensusService consensusService;

  private final CountDownLatch shutdown = new CountDownLatch(1);

  @Override
  public void setOptions(Args args) {
    // not used
  }

  @Override
  @Autowired
  public void init(CommonParameter parameter) {
    // not used
  }

  @Override
  public void addService(Service service) {
    // used by test
  }

  @Override
  public void initServices(CommonParameter parameter) {
    // not used
  }

  /**
   * start up the app.
   */
  public void startup() {
    this.startServices();
    if ((!Args.getInstance().isSolidityNode()) && (!Args.getInstance().isP2pDisable())) {
      tronNetService.start();
    }
    consensusService.start();
    MetricsUtil.init();
  }

  @Override
  public void shutdown() {
    this.shutdownServices();
    consensusService.stop();
    if (!Args.getInstance().isSolidityNode() && (!Args.getInstance().p2pDisable)) {
      tronNetService.close();
    }
    dbManager.close();
    shutdown.countDown();
  }

  @Override
  public void startServices() {
    try {
      services.start();
    } catch (Exception e) {
      logger.error("Failed to start services", e);
      System.exit(1);
    }
  }

  @Override
  public void blockUntilShutdown() {
    try {
      shutdown.await();
    } catch (final InterruptedException e) {
      logger.debug("Interrupted, exiting", e);
      Thread.currentThread().interrupt();
    }
  }

  @Override
  public void shutdownServices() {
    services.stop();
  }

  @Override
  public Manager getDbManager() {
    return dbManager;
  }

  @Override
  public ChainBaseManager getChainBaseManager() {
    return chainBaseManager;
  }

}
