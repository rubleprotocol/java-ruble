package org.tron.program;

import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import java.io.File;
import java.nio.file.Paths;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.ExpectedException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.tron.api.WalletGrpc;
import org.tron.common.application.Application;
import org.tron.common.application.ApplicationFactory;
import org.tron.common.application.TronApplicationContext;
import org.tron.common.config.DbBackupConfig;
import org.tron.common.crypto.ECKey;
import org.tron.common.utils.FileUtil;
import org.tron.common.utils.PublicMethod;
import org.tron.common.utils.Utils;
import org.tron.core.config.DefaultConfig;
import org.tron.core.config.args.Args;
import org.tron.core.services.RpcApiService;
import org.tron.core.services.interfaceOnSolidity.RpcApiServiceOnSolidity;
import org.tron.tool.litefullnode.LiteFullNodeTool;

public class LiteFullNodeToolTest {

  private static final Logger logger = LoggerFactory.getLogger("Test");

  private TronApplicationContext context;
  private WalletGrpc.WalletBlockingStub blockingStubFull = null;
  private Application appTest;

  private String databaseDir;

  @Rule
  public ExpectedException thrown = ExpectedException.none();

  private static String dbPath = "output_lite_fn";

  /**
   * init logic.
   */
  public void startApp() {
    context = new TronApplicationContext(DefaultConfig.class);
    appTest = ApplicationFactory.create(context);
    appTest.addService(context.getBean(RpcApiService.class));
    appTest.addService(context.getBean(RpcApiServiceOnSolidity.class));
    appTest.initServices(Args.getInstance());
    appTest.startServices();
    appTest.startup();

    String fullnode = String.format("%s:%d", "127.0.0.1",
            Args.getInstance().getRpcPort());
    ManagedChannel channelFull = ManagedChannelBuilder.forTarget(fullnode)
            .usePlaintext(true)
            .build();
    blockingStubFull = WalletGrpc.newBlockingStub(channelFull);
  }

  /**
   *  Delete the database when exit.
   */
  public static void destroy(String dbPath) {
    File f = new File(dbPath);
    if (f.exists()) {
      if (FileUtil.deleteDir(f)) {
        logger.info("Release resources successful.");
      } else {
        logger.info("Release resources failure.");
      }
    }
  }

  /**
   * shutdown the fullnode.
   */
  public void shutdown() {
    appTest.shutdownServices();
    appTest.shutdown();
    context.destroy();
  }

  public void init() {
    destroy(dbPath); // delete if prev failed
    Args.setParam(new String[]{"-d", dbPath, "-w"}, "config-localtest.conf");
    // allow account root
    Args.getInstance().setAllowAccountStateRoot(1);
    databaseDir = Args.getInstance().getStorage().getDbDirectory();
    // init dbBackupConfig to avoid NPE
    Args.getInstance().dbBackupConfig = DbBackupConfig.getInstance();
  }

  @After
  public void clear() {
    destroy(dbPath);
    Args.clearParam();
  }

  @Test
  public void testToolsWithLevelDB() {
    logger.info("testToolsWithLevelDB start");
    testTools("LEVELDB", 1);
  }

  @Test
  public void testToolsWithLevelDBV2() {
    logger.info("testToolsWithLevelDB start");
    testTools("LEVELDB", 2);
  }

  @Test
  public void testToolsWithRocksDB() {
    logger.info("testToolsWithRocksDB start");
    testTools("ROCKSDB", 1);
  }

  private void testTools(String dbType, int checkpointVersion) {
    dbPath = String.format("%s_%s_%d", dbPath, dbType, checkpointVersion);
    init();
    final String[] argsForSnapshot =
        new String[]{"-o", "split", "-t", "snapshot", "--fn-data-path",
            dbPath + File.separator + databaseDir, "--dataset-path",
            dbPath};
    final String[] argsForHistory =
        new String[]{"-o", "split", "-t", "history", "--fn-data-path",
            dbPath + File.separator + databaseDir, "--dataset-path",
            dbPath};
    final String[] argsForMerge =
        new String[]{"-o", "merge", "--fn-data-path", dbPath + File.separator + databaseDir,
            "--dataset-path", dbPath + File.separator + "history"};
    Args.getInstance().getStorage().setDbEngine(dbType);
    Args.getInstance().getStorage().setCheckpointVersion(checkpointVersion);
    LiteFullNodeTool.setRecentBlks(3);
    // start fullnode
    startApp();
    // produce transactions for 18 seconds
    generateSomeTransactions(18);
    // stop the node
    shutdown();
    // delete tran-cache
    FileUtil.deleteDir(Paths.get(dbPath, databaseDir, "trans-cache").toFile());
    // generate snapshot
    LiteFullNodeTool.main(argsForSnapshot);
    // start fullnode
    startApp();
    // produce transactions for 6 seconds
    generateSomeTransactions(6);
    // stop the node
    shutdown();
    // generate history
    LiteFullNodeTool.main(argsForHistory);
    // backup original database to database_bak
    File database = new File(Paths.get(dbPath, databaseDir).toString());
    if (!database.renameTo(new File(Paths.get(dbPath, databaseDir + "_bak").toString()))) {
      throw new RuntimeException(
              String.format("rename %s to %s failed", database.getPath(),
                      Paths.get(dbPath, databaseDir).toString()));
    }
    // change snapshot to the new database
    File snapshot = new File(Paths.get(dbPath, "snapshot").toString());
    if (!snapshot.renameTo(new File(Paths.get(dbPath, databaseDir).toString()))) {
      throw new RuntimeException(
              String.format("rename snapshot to %s failed",
                      Paths.get(dbPath, databaseDir).toString()));
    }
    // start and validate the snapshot
    startApp();
    generateSomeTransactions(6);
    // stop the node
    shutdown();
    // merge history
    LiteFullNodeTool.main(argsForMerge);
    // start and validate
    startApp();
    generateSomeTransactions(6);
    shutdown();
    LiteFullNodeTool.reSetRecentBlks();
  }

  private void generateSomeTransactions(int during) {
    during *= 1000; // ms
    int runTime = 0;
    int sleepOnce = 100;
    while (true) {
      ECKey ecKey2 = new ECKey(Utils.getRandom());
      byte[] address = ecKey2.getAddress();

      String sunPri = "cba92a516ea09f620a16ff7ee95ce0df1d56550a8babe9964981a7144c8a784a";
      byte[] sunAddress = PublicMethod.getFinalAddress(sunPri);
      PublicMethod.sendcoin(address, 1L,
              sunAddress, sunPri, blockingStubFull);
      try {
        Thread.sleep(sleepOnce);
      } catch (InterruptedException e) {
        e.printStackTrace();
      }
      if ((runTime += sleepOnce) > during) {
        return;
      }
    }
  }
}
