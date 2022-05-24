package org.tron.consensus.dpos;

import com.google.protobuf.ByteString;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.tron.common.entity.Dec;
import org.tron.consensus.ConsensusDelegate;
import org.tron.core.capsule.BlockCapsule;
import org.tron.core.capsule.WitnessCapsule;
import org.tron.core.store.OracleStore;
import org.tron.protos.Protocol;


@Slf4j(topic = "consensus")
@Component
public class OracleManager {

  static class ExchangeRateData implements Comparable<ExchangeRateData> {
    private final ByteString srAddress;
    private long vote;
    private Dec exchangeRate;

    public ExchangeRateData(ByteString srAddress, long vote, Dec exchangeRate) {
      this.srAddress = srAddress;
      this.vote = vote;
      this.exchangeRate = exchangeRate;
    }

    @Override
    public int compareTo(ExchangeRateData e) {
      return Long.compare(vote, e.vote);
    }

    @Override
    public boolean equals(Object o) {
      if (this == o) {
        return true;
      }
      if (o == null || getClass() != o.getClass()) {
        return false;
      }
      ExchangeRateData that = (ExchangeRateData) o;
      return vote == that.vote && srAddress.equals(that.srAddress)
          && Objects.equals(exchangeRate, that.exchangeRate);
    }

    @Override
    public int hashCode() {
      return Objects.hash(srAddress, vote, exchangeRate);
    }
  }

  // Claim is an interface that directs its rewards to sr account.
  static class Claim {
    private long vote;
    private long weight;
    private long winCount;
    private final ByteString srAddress;

    public Claim(ByteString srAddress, long vote) {
      this.srAddress = srAddress;
      this.weight = 0;
      this.vote = vote;
      this.winCount = 0;
    }
  }

  @Autowired
  private ConsensusDelegate consensusDelegate;

  public void applyBlock(BlockCapsule blockCapsule) {
    final long votePeriod = consensusDelegate.getDynamicPropertiesStore().getOracleVotePeriod();
    final long blockNum = blockCapsule.getNum();

    if (votePeriod == 0) {
      return;
    }
    //check period last block
    if ((blockNum + 1) % votePeriod == 0) {
      // Build claim map over all srs in active set
      Map<ByteString, Claim> srMap = new HashMap<>();
      long totalVote = 0;
      for (WitnessCapsule witness : consensusDelegate.getAllWitnesses()) {
        ByteString sr = witness.getAddress();
        srMap.put(sr, new Claim(sr, witness.getVoteCount()));
        totalVote += witness.getVoteCount();
      }

      // get white list
      OracleStore oracleStore = consensusDelegate.getOracleStore();
      Map<String, Dec> supportAssets = oracleStore.getAllTobinTax();

      // 1. clear old exchange rates
      oracleStore.clearAllExchangeRates();

      // 2, sorting ballots
      Map<String, List<ExchangeRateData>> assetVotes =
          organizeBallotByAsset(srMap, supportAssets);

      // 3. pick reference Asset
      final long thresholdVotes = totalVote / 100
          * consensusDelegate.getDynamicPropertiesStore().getOracleVoteThreshold();
      String referenceAsset = pickReferenceAsset(assetVotes, thresholdVotes);

      // 4. calculate cross exchange rates
      if (referenceAsset != null) {
        // make voteMap of Reference Asset to calculate cross exchange rates
        List<ExchangeRateData> voteReferenceList = assetVotes.get(referenceAsset);
        // save reference asset exchange rate
        Dec exchangeRateReference = getWeightedMedian(voteReferenceList);
        oracleStore.setTrxExchangeRate(referenceAsset, exchangeRateReference);

        // save other assets exchange rate
        Map<ByteString, Dec> voteReferenceMap = new HashMap<>();
        voteReferenceList.forEach(vote ->
            voteReferenceMap.put(vote.srAddress, vote.exchangeRate));
        assetVotes.forEach((token, voteList) -> {
          if (!token.equals(referenceAsset)) {
            // Convert vote to cross exchange rates
            toCrossRate(voteList, voteReferenceMap);
            Dec exchangeRate = tally(voteList, srMap);
            exchangeRate = exchangeRateReference.quo(exchangeRate);
            oracleStore.setTrxExchangeRate(token, exchangeRate);
          }
        });
      }

      // 5. post-processing, clear vote info, update tobin tax
      oracleStore.clearPrevoteAndVotes(blockNum, votePeriod);
      // TODO replace first param with proposal tobin list
      oracleStore.updateTobinTax(supportAssets, supportAssets);
    }
  }

  // NOTE: **Make abstain votes to have zero vote**
  private Map<String, List<ExchangeRateData>> organizeBallotByAsset(
      Map<ByteString, Claim> srMap, Map<String, Dec> whiteList) {
    Map<String, List<ExchangeRateData>> tokenVotes = new HashMap<>();
    // Organize aggregate votes
    for (Map.Entry<ByteString, Claim> entry : srMap.entrySet()) {
      ByteString sr = entry.getKey();
      Claim srInfo = entry.getValue();
      OracleStore oracleStore = consensusDelegate.getOracleStore();
      Protocol.OracleVote srVote = oracleStore.getVote(sr.toByteArray());
      if (srVote == null) {
        continue;
      }
      String exchangeRateStr = srVote.getExchangeRates();

      // check all assets are in the vote whitelist
      Map<String, Dec> exchangeRateMap = OracleStore.parseExchangeRateTuples(exchangeRateStr);
      exchangeRateMap.forEach((asset, exchangeRate) -> {
        if (whiteList.get(asset) != null) {
          List<ExchangeRateData> tokenRateList =
              tokenVotes.computeIfAbsent(asset, k -> new ArrayList<>());

          long tmpVote = srInfo.vote;
          if (!exchangeRate.isPositive()) {
            tmpVote = 0;
          }
          tokenRateList.add(new ExchangeRateData(sr, tmpVote, exchangeRate));
        }
      });
    }

    tokenVotes.forEach((key, voteList) -> Collections.sort(voteList));
    return tokenVotes;
  }

  private String pickReferenceAsset(
      Map<String, List<ExchangeRateData>> assetVotes, long thresholdVotes) {
    long largestVote = 0;
    String referenceAsset = "";

    for (Map.Entry<String, List<ExchangeRateData>> assetVote : assetVotes.entrySet()) {
      long tokenVoteCounts = assetVote.getValue().stream().mapToLong(vote -> vote.vote).sum();
      String asset = assetVote.getKey();

      // check token vote count
      if (tokenVoteCounts < thresholdVotes) {
        assetVotes.remove(asset);
      }

      if (tokenVoteCounts > largestVote || largestVote == 0) {
        referenceAsset = asset;
        largestVote = tokenVoteCounts;
      } else if (largestVote == tokenVoteCounts && referenceAsset.compareTo(asset) > 0) {
        referenceAsset = asset;
      }
    }
    return referenceAsset;
  }

  private Dec getWeightedMedian(List<ExchangeRateData> voteReferenceList) {
    if (!voteReferenceList.isEmpty()) {
      long tokenVoteCounts = voteReferenceList.stream().mapToLong(vote -> vote.vote).sum();
      long currentVote = 0;
      for (ExchangeRateData vote : voteReferenceList) {
        currentVote += vote.vote;
        if (currentVote >= (tokenVoteCounts / 2)) {
          return vote.exchangeRate;
        }
      }
    }
    return Dec.zeroDec();
  }

  private void toCrossRate(
      List<ExchangeRateData> voteList, Map<ByteString, Dec> referenceVote) {
    voteList.forEach(vote -> {
      Dec srReferenceRate = referenceVote.get(vote.srAddress);
      if (srReferenceRate.isPositive()) {
        vote.exchangeRate = srReferenceRate.quo(vote.exchangeRate);
      } else {
        vote.exchangeRate = Dec.zeroDec();
        vote.vote = 0;
      }
    });
    Collections.sort(voteList);
  }

  private Dec tally(List<ExchangeRateData> voteList, Map<ByteString, Claim> srClaim) {
    // TODO add reward process
    srClaim.forEach((sr, claim) -> logger.debug(
        "sr=" + claim.srAddress.toString()
            + ", weight=" + claim.weight
            + " win=" + claim.winCount));
    return getWeightedMedian(voteList);
  }

}
