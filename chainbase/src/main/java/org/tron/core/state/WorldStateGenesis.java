package org.tron.core.state;

import static org.fusesource.leveldbjni.JniDBFactory.factory;

import com.google.common.collect.Maps;
import java.io.Closeable;
import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import javax.annotation.PostConstruct;
import javax.annotation.PreDestroy;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.apache.tuweni.bytes.Bytes;
import org.apache.tuweni.bytes.Bytes32;
import org.iq80.leveldb.DBException;
import org.iq80.leveldb.DBIterator;
import org.rocksdb.BlockBasedTableConfig;
import org.rocksdb.BloomFilter;
import org.rocksdb.ComparatorOptions;
import org.rocksdb.LRUCache;
import org.rocksdb.Options;
import org.rocksdb.ReadOptions;
import org.rocksdb.RocksDBException;
import org.rocksdb.RocksIterator;
import org.springframework.stereotype.Component;
import org.tron.common.parameter.CommonParameter;
import org.tron.common.utils.DbOptionalsUtils;
import org.tron.common.utils.FileUtil;
import org.tron.common.utils.MarketOrderPriceComparatorForLevelDB;
import org.tron.common.utils.MarketOrderPriceComparatorForRockDB;
import org.tron.common.utils.PropUtil;
import org.tron.core.ChainBaseManager;
import org.tron.core.capsule.BlockCapsule;
import org.tron.core.db.BlockStore;
import org.tron.core.exception.HeaderNotFound;

@Component("worldStateGenesis")
@Slf4j(topic = "DB")
public class WorldStateGenesis {

  private ChainBaseManager chainBaseManager;

  private final boolean allowStateRoot = CommonParameter.getInstance().getStorage()
      .isAllowStateRoot();

  private Path stateGenesisPath = Paths.get(CommonParameter.getInstance().getStorage()
      .getStateGenesisDirectory());

  @Getter
  private long stateGenesisHeight;

  private long genesisHeight;

  private static final String STATE_GENESIS_PROPERTIES = "genesis.properties";

  private static final String STATE_GENESIS_HEIGHT = "height";

  private final Map<StateType, DB> genesisDBs = Maps.newConcurrentMap();

  private volatile boolean inited = false;

  private static final String KEY_ENGINE = "ENGINE";
  private static final String ENGINE_FILE = "engine.properties";
  private static final String ROCKSDB = "ROCKSDB";

  public synchronized void init(ChainBaseManager chainBaseManager) {
    if (!allowStateRoot) {
      return;
    }
    this.chainBaseManager = chainBaseManager;
    genesisHeight = chainBaseManager.getGenesisBlockId().getNum();
    tryInitGenesis();
    initGenesisDBs();
    inited = true;
  }

  @PostConstruct
  private void open() {
    if (!stateGenesisPath.isAbsolute()) {
      stateGenesisPath = Paths.get(CommonParameter.getInstance().getOutputDirectory(),
          CommonParameter.getInstance().getStorage().getStateGenesisDirectory());
    }
  }

  @PreDestroy
  private void close() {
    if (!inited) {
      return;
    }
    genesisDBs.values().forEach(db -> {
      try {
        db.close();
      } catch (IOException e) {
        logger.warn(db.name(), e.getMessage());
      }
    });
    genesisDBs.clear();
  }

  public byte[] get(StateType type, byte[] key) {
    if (!allowStateRoot) {
      throw new IllegalStateException("StateRoot is not allowed.");
    }
    if (!inited) {
      throw new IllegalStateException("StateRoot is not inited.");
    }

    if (stateGenesisHeight == 0) {
      return null;
    }

    if (stateGenesisHeight > genesisHeight) {
      try {
        return genesisDBs.get(type).get(key);
      } catch (RocksDBException | DBException e) {
        throw new RuntimeException(type.getName(), e);
      }
    } else {
      throw new IllegalStateException("stateGenesis is not available.");
    }
  }

  public Map<Bytes, Bytes> prefixQuery(StateType type, byte[] key) {
    if (!allowStateRoot) {
      throw new IllegalStateException("StateRoot is not allowed.");
    }
    if (!inited) {
      throw new IllegalStateException("StateRoot is not inited.");
    }

    if (stateGenesisHeight == 0) {
      return Collections.emptyMap();
    }

    if (stateGenesisHeight > genesisHeight) {
      return genesisDBs.get(type).prefixQuery(key);
    } else {
      throw new IllegalStateException("stateGenesis is not available.");
    }
  }

  private void tryInitGenesis() {
    if ((this.stateGenesisHeight = tryFindStateGenesisHeight()) > -1) {
      return;
    }
    // copy state db
    initGenesis();
    // reset root
    resetArchiveRoot();
    // init genesis properties
    initGenesisProperties();
  }

  public void resetArchiveRoot() {
    BlockStore blockStore = chainBaseManager.getBlockStore();
    BlockCapsule blockCapsule = null;
    try {
      blockCapsule = chainBaseManager.getHead();
      if (blockCapsule.getNum() == genesisHeight) {
        logger.debug("skip reset archive root in case of the head is 0");
        return;
      }
    } catch (HeaderNotFound e) {
      logger.error("reset archive root failed, err: {}", e.getMessage());
      System.exit(1);
    }
    logger.info("reset archive root, number: {}, prev root: {}",
        blockCapsule.getNum(),
        blockCapsule.getArchiveRoot());
    blockCapsule.setArchiveRoot(Bytes32.ZERO.toArray());
    blockStore.put(blockCapsule.getBlockId().getBytes(), blockCapsule);
  }

  private void initGenesisDBs() {
    if (this.stateGenesisHeight == genesisHeight) {
      return;
    }
    Arrays.stream(StateType.values()).filter(type -> type != StateType.UNDEFINED)
        .parallel().forEach(type -> {
          try {
            genesisDBs.put(type, getDb(stateGenesisPath, type.getName()));
          } catch (RocksDBException | IOException e) {
            throw new RuntimeException(e);
          }
        });
  }

  private DB getDb(Path sourceDir, String dbName) throws RocksDBException, IOException {
    File engineFile = Paths.get(sourceDir.toString(), dbName, ENGINE_FILE).toFile();
    if (!engineFile.exists()) {
      return new LevelDB(sourceDir, dbName);
    }
    String engine = PropUtil.readProperty(engineFile.toString(), KEY_ENGINE);
    if (engine.equalsIgnoreCase(ROCKSDB)) {
      return new RocksDB(sourceDir, dbName);
    } else {
      return new LevelDB(sourceDir, dbName);
    }
  }

  private void initGenesis() {
    logger.info("State genesis init start");
    FileUtil.createDirIfNotExists(stateGenesisPath.toString());
    long height = chainBaseManager.getHeadBlockNum();
    if (height == genesisHeight) {
      logger.info("Skip state genesis init since head is {}", height);
      return;
    }
    List<String> dbs = Arrays.stream(StateType.values())
        .filter(type -> type != StateType.UNDEFINED)
        .map(StateType::getName).collect(Collectors.toList());
    Path source = Paths.get(CommonParameter.getInstance().getOutputDirectory(),
        CommonParameter.getInstance().getStorage().getDbDirectory());
    // check dbs if exist
    List<String> miss = dbs.stream().map(db -> Paths.get(source.toString(), db)
        .toFile()).filter(db -> !db.exists()).map(File::getName).collect(Collectors.toList());
    if (!miss.isEmpty()) {
      logger.error("Corrupted source path, miss : {}", miss);
      throw new IllegalArgumentException(String.format("Corrupted source path: %s", miss));
    }
    // delete if exit
    dbs.stream().map(db -> Paths.get(stateGenesisPath.toString(), db).toFile())
        .filter(File::exists).forEach(dir -> {
          logger.info("Delete corrupted state genesis path : {}", dir);
          FileUtil.deleteDir(dir);
        });
    FileUtil.copyDatabases(source, stateGenesisPath, dbs);
    logger.info("State genesis init end, {}, {}", stateGenesisPath, dbs);
  }

  private void initGenesisProperties() {
    logger.info("State genesis properties init start");
    long height = chainBaseManager.getHeadBlockNum();
    Map<String, String> properties = new HashMap<>();
    properties.put(STATE_GENESIS_HEIGHT, String.valueOf(height));
    String genesisFile = new File(stateGenesisPath.toString(), STATE_GENESIS_PROPERTIES).toString();
    PropUtil.writeProperties(genesisFile, properties);
    this.stateGenesisHeight =  height;
    logger.info("State genesis properties init end, detail: {}", properties);

  }

  private long tryFindStateGenesisHeight() {
    // Read "genesis.properties" file, which contains a pointer to the current header
    File genesisFile = new File(stateGenesisPath.toString(), STATE_GENESIS_PROPERTIES);
    if (!genesisFile.exists()) {
      return -1;
    }

    String height = PropUtil.readProperty(genesisFile.toString(), STATE_GENESIS_HEIGHT);
    if (StringUtils.isEmpty(height)) {
      return -1;
    }
    long header = Long.parseLong(height);
    logger.info("State genesis header :{}", header);
    return header;
  }


  interface DB extends Closeable {
    byte[] get(byte[] key) throws RocksDBException;

    Map<Bytes, Bytes> prefixQuery(byte[] key);

    String name();
  }

  static class RocksDB  implements DB {

    private final org.rocksdb.RocksDB rocksDB;
    private final String name;

    private static final String MARKET_PAIR_PRICE_TO_ORDER = "market_pair_price_to_order";

    private static final LRUCache CACHE = new LRUCache(128 * 1024 * 1024L);

    public RocksDB(Path path, String name) throws RocksDBException {
      this.name = name;
      this.rocksDB = newRocksDbReadOnly(Paths.get(path.toString(), name));
    }

    @Override
    public byte[] get(byte[] key) throws RocksDBException {
      return this.rocksDB.get(key);
    }

    @Override
    public Map<Bytes, Bytes> prefixQuery(byte[] key) {
      try (ReadOptions readOptions = new ReadOptions().setFillCache(false);
           RocksIterator iterator = this.rocksDB.newIterator(readOptions)) {
        Map<Bytes, Bytes> result = new HashMap<>();
        for (iterator.seek(key); iterator.isValid(); iterator.next()) {
          if (com.google.common.primitives.Bytes.indexOf(iterator.key(), key) == 0) {
            result.put(Bytes.wrap(iterator.key()), Bytes.wrap(iterator.value()));
          } else {
            return result;
          }
        }
        return result;
      }
    }

    @Override
    public String name() {
      return this.name;
    }

    private org.rocksdb.RocksDB newRocksDbReadOnly(Path db) throws RocksDBException {
      try (Options options = newDefaultRocksDbOptions()) {
        if (MARKET_PAIR_PRICE_TO_ORDER.equalsIgnoreCase(db.getFileName().toString())) {
          options.setComparator(new MarketOrderPriceComparatorForRockDB(new ComparatorOptions()));
        }
        return org.rocksdb.RocksDB.openReadOnly(options, db.toString());
      }
    }

    private Options newDefaultRocksDbOptions() {
      Options options = new Options();
      options.setCreateIfMissing(false);
      options.setIncreaseParallelism(1);
      options.setNumLevels(7);
      options.setMaxOpenFiles(100);
      options.setTargetFileSizeBase(64 * 1024 * 1024);
      options.setTargetFileSizeMultiplier(1);
      options.setMaxBytesForLevelBase(512 * 1024 * 1024);
      options.setMaxBackgroundCompactions(Math.max(1, Runtime.getRuntime().availableProcessors()));
      options.setLevel0FileNumCompactionTrigger(4);
      options.setLevelCompactionDynamicLevelBytes(true);
      final BlockBasedTableConfig tableCfg;
      options.setTableFormatConfig(tableCfg = new BlockBasedTableConfig());
      tableCfg.setBlockSize(64 * 1024);
      tableCfg.setBlockCache(CACHE);
      tableCfg.setCacheIndexAndFilterBlocks(true);
      tableCfg.setPinL0FilterAndIndexBlocksInCache(true);
      tableCfg.setFilter(new BloomFilter(10, false));
      return options;
    }

    @Override
    public void close() throws IOException {
      this.rocksDB.close();
    }
  }

  static class LevelDB  implements DB {

    private final org.iq80.leveldb.DB levelDB;
    private final String name;

    private static final String MARKET_PAIR_PRICE_TO_ORDER = "market_pair_price_to_order";

    public LevelDB(Path path, String name) throws IOException {
      this.name = name;
      this.levelDB = newLevelDb(Paths.get(path.toString(), name));
    }

    @Override
    public byte[] get(byte[] key) throws DBException {
      return this.levelDB.get(key);
    }

    @Override
    public Map<Bytes, Bytes> prefixQuery(byte[] key) {
      try (DBIterator iterator = this.levelDB.iterator(
          new org.iq80.leveldb.ReadOptions().fillCache(false))) {
        Map<Bytes, Bytes> result = new HashMap<>();
        for (iterator.seek(key); iterator.hasNext(); iterator.next()) {
          Map.Entry<byte[], byte[]> entry = iterator.peekNext();
          if (com.google.common.primitives.Bytes.indexOf(entry.getKey(), key) == 0) {
            result.put(Bytes.wrap(entry.getKey()), Bytes.wrap(entry.getValue()));
          } else {
            return result;
          }
        }
        return result;
      } catch (IOException e) {
        throw new RuntimeException(e);
      }
    }

    @Override
    public String name() {
      return this.name;
    }

    private org.iq80.leveldb.DB newLevelDb(Path db) throws IOException {
      org.iq80.leveldb.Options options = createDefaultDbOptions();
      if (MARKET_PAIR_PRICE_TO_ORDER.equalsIgnoreCase(db.getFileName().toString())) {
        options.comparator(new MarketOrderPriceComparatorForLevelDB());
      }
      return  factory.open(db.toFile(), options);
    }

    private org.iq80.leveldb.Options createDefaultDbOptions() {
      org.iq80.leveldb.Options options = new org.iq80.leveldb.Options();
      options.createIfMissing(false);
      options.paranoidChecks(true);
      options.verifyChecksums(true);

      options.compressionType(DbOptionalsUtils.DEFAULT_COMPRESSION_TYPE);
      options.blockSize(DbOptionalsUtils.DEFAULT_BLOCK_SIZE);
      options.writeBufferSize(DbOptionalsUtils.DEFAULT_WRITE_BUFFER_SIZE);
      options.cacheSize(DbOptionalsUtils.DEFAULT_CACHE_SIZE);
      options.maxOpenFiles(DbOptionalsUtils.DEFAULT_MAX_OPEN_FILES);
      return options;
    }

    @Override
    public void close() throws IOException {
      this.levelDB.close();
    }
  }


}
