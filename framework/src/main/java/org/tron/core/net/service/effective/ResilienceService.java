package org.tron.core.net.service.effective;

import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.tron.common.es.ExecutorServiceManager;
import org.tron.common.parameter.CommonParameter;
import org.tron.common.parameter.ResilienceConfig;
import org.tron.core.ChainBaseManager;
import org.tron.core.net.TronNetDelegate;
import org.tron.core.net.peer.PeerConnection;
import org.tron.protos.Protocol.ReasonCode;

@Slf4j(topic = "net")
@Component
public class ResilienceService {

  private final ResilienceConfig resilienceConfig = CommonParameter.getInstance()
      .getResilienceConfig();

  @Autowired
  private TronNetDelegate tronNetDelegate;

  @Autowired
  private ChainBaseManager chainBaseManager;

  private final String esName = "resilience-service";
  private ScheduledExecutorService executor;


  public void init() {
    if (resilienceConfig.isEnabled()) {
      executor = ExecutorServiceManager.newSingleThreadScheduledExecutor(esName);
      executor.scheduleWithFixedDelay(() -> {
        try {
          resilienceNode();
        } catch (Exception e) {
          logger.error("Resilience node failed", e);
        }
      }, 5 * 60, resilienceConfig.getCheckInterval(), TimeUnit.SECONDS);
    } else {
      logger.info("ResilienceService is disabled");
    }
  }


  public void resilienceNode() {
    if (!resilienceConfig.isTestStopInv()) {
      //update peers' bad feature 3 at first
      tronNetDelegate.getActivePeer()
          .forEach(peer -> peer.getMaliciousFeature().updateBadFeature3());
    }

    int peerSize = tronNetDelegate.getActivePeer().size();
    int activePeerSize = (int) tronNetDelegate.getActivePeer().stream()
        .filter(peer -> peer.getChannel().isActive())
        .count();
    int findCount = 0;

    //1. if local node belongs to a lan network, disconnect with first malicious node if necessary
    if (peerSize == activePeerSize && peerSize >= CommonParameter.getInstance().minConnections) {
      findCount = findAndDisconnect(false, "case1") ? 1 : 0;
    }

    //2. if local node's latestSaveBlockTime has not changed more than several minutes,
    // it is isolated, we need to disconnect with some peers
    if (findCount == 0) {
      int advPeerCount = (int) tronNetDelegate.getActivePeer().stream()
          .filter(peer -> !peer.isNeedSyncFromPeer() && !peer.isNeedSyncFromUs())
          .count();
      long diff = System.currentTimeMillis() - chainBaseManager.getLatestSaveBlockTime();
      if (advPeerCount >= 1 && peerSize >= CommonParameter.getInstance().minConnections
          && diff >= resilienceConfig.getBlockNotChangeThreshold() * 1000L) {
        logger.warn("Node has been isolated for {} ms, try to disconnect some peers", diff);

        //prefer to disconnect with active peer. if all are same, choose the oldest
        List<PeerConnection> peerList = tronNetDelegate.getActivePeer().stream()
            .filter(peer -> !peer.isDisconnect())
            .filter(peer -> !peer.getChannel().isTrustPeer())
            .filter(PeerConnection::isMalicious)
            .sorted((o1, o2) -> {
              if (o1.getChannel().isActive() && !o2.getChannel().isActive()) {
                return -1;
              } else if (!o1.getChannel().isActive() && o2.getChannel().isActive()) {
                return 1;
              } else {
                return Long.compare(o1.getMaliciousFeature().getEarliestTime(),
                    o2.getMaliciousFeature().getEarliestTime());
              }
            })
            .collect(Collectors.toList());

        //choose most disconnectNumber peer
        if (peerList.size() >= resilienceConfig.getDisconnectNumber()) {
          peerList = peerList.subList(0, resilienceConfig.getDisconnectNumber());
        }
        if (!peerList.isEmpty()) {
          peerList.forEach(peer -> disconnectMaliciousPeer(peer, "case2"));
          findCount = peerList.size();
        }
      }
    }

    //3. if peers' number is equal or larger than maxConnections, disconnect with oldest peer
    if (findCount == 0 && peerSize >= CommonParameter.getInstance().maxConnections) {
      findCount = findAndDisconnect(true, "case3") ? 1 : 0;
    }

    if (findCount > 0) {
      logger.info("Disconnect with {} malicious peer", findCount);
    }
  }

  private boolean findAndDisconnect(boolean excludeActive, String condition) {
    Optional<PeerConnection> p = tronNetDelegate.getActivePeer().stream()
        .filter(peer -> !peer.isDisconnect())
        .filter(peer -> !peer.getChannel().isTrustPeer())
        .filter(PeerConnection::isMalicious)
        .filter(peer -> !excludeActive || !peer.getChannel().isActive())
        .min(Comparator.comparing(peer -> peer.getMaliciousFeature().getEarliestTime(),
            Long::compareTo));

    if (p.isPresent()) {
      disconnectMaliciousPeer(p.get(), condition);
      return true;
    }
    return false;
  }

  private void disconnectMaliciousPeer(PeerConnection p, String condition) {
    logger.info("feature {}: {}, condition:{}", p.getInetSocketAddress(), p.getMaliciousFeature(),
        condition);
    p.disconnect(ReasonCode.MALICIOUS_NODE);
  }

  public void close() {
    ExecutorServiceManager.shutdownAndAwaitTermination(executor, esName);
  }
}
