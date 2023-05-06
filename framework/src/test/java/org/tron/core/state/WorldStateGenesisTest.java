package org.tron.core.state;

import com.google.protobuf.ByteString;
import java.io.IOException;
import org.apache.tuweni.bytes.Bytes32;
import org.junit.After;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.rules.TemporaryFolder;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.junit.jupiter.SpringExtension;
import org.tron.common.application.Application;
import org.tron.common.application.ApplicationFactory;
import org.tron.common.application.TronApplicationContext;
import org.tron.common.config.DbBackupConfig;
import org.tron.common.crypto.ECKey;
import org.tron.common.utils.Sha256Hash;
import org.tron.core.ChainBaseManager;
import org.tron.core.capsule.BlockCapsule;
import org.tron.core.config.DefaultConfig;
import org.tron.core.config.args.Args;
import org.tron.core.exception.HeaderNotFound;

@ExtendWith(SpringExtension.class)
@ContextConfiguration
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
public class WorldStateGenesisTest {
  private static TronApplicationContext context;
  private static Application appTest;
  private static ChainBaseManager chainBaseManager;
  private static WorldStateGenesis worldStateGenesis;

  @Rule
  public final TemporaryFolder temporaryFolder = new TemporaryFolder();

  /**
   * init logic.
   */
  @Before
  public void init() throws IOException {

    Args.setParam(new String[]{"-d",  temporaryFolder.newFolder().toString()},
        "config-localtest.conf");
    // allow account root
    Args.getInstance().setAllowAccountStateRoot(1);
    // init dbBackupConfig to avoid NPE
    Args.getInstance().dbBackupConfig = DbBackupConfig.getInstance();
    context = new TronApplicationContext(DefaultConfig.class);
    appTest = ApplicationFactory.create(context);
    appTest.startup();
    chainBaseManager = context.getBean(ChainBaseManager.class);
    worldStateGenesis = context.getBean(WorldStateGenesis.class);
  }

  @After
  public void destroy() {
    context.destroy();
    Args.clearParam();
  }

  @Test
  public void testResetArchiveRoot() throws HeaderNotFound {
    // test ignore genesis reset
    worldStateGenesis.resetArchiveRoot();
    Assert.assertNotEquals(Bytes32.ZERO, chainBaseManager.getHead().getArchiveRoot());

    // test reset
    BlockCapsule parentBlock = chainBaseManager.getHead();
    BlockCapsule blockCapsule =
        new BlockCapsule(
            parentBlock.getNum() + 1,
            Sha256Hash.wrap(parentBlock.getBlockId().getByteString()),
            System.currentTimeMillis(),
            ByteString.copyFrom(
                ECKey.fromPrivate(
                    org.tron.common.utils.ByteArray.fromHexString(
                        Args.getLocalWitnesses().getPrivateKey()))
                    .getAddress()));
    blockCapsule.setMerkleRoot();
    blockCapsule.setArchiveRoot(Bytes32.random().toArray());
    chainBaseManager.getBlockStore().put(blockCapsule.getBlockId().getBytes(), blockCapsule);

    worldStateGenesis.resetArchiveRoot();
    Assert.assertEquals(Bytes32.ZERO, chainBaseManager.getHead().getArchiveRoot());
  }

}
