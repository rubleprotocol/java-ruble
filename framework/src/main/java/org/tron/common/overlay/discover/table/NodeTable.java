/*
 * Copyright (c) [2016] [ <ether.camp> ]
 * This file is part of the ethereumJ library.
 *
 * The ethereumJ library is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Lesser General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * The ethereumJ library is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 * GNU Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License
 * along with the ethereumJ library. If not, see <http://www.gnu.org/licenses/>.
 */

package org.tron.common.overlay.discover.table;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import lombok.extern.slf4j.Slf4j;
import org.tron.common.overlay.discover.node.Node;

@Slf4j(topic = "discover")
public class NodeTable {

  private final Node node;  // our node
  private transient NodeBucket[] buckets;
  private transient List<NodeEntry> nodes;

  public NodeTable(Node n) {
    this.node = n;
    initialize();
  }

  public Node getNode() {
    return node;
  }

  public final void initialize() {
    nodes = new ArrayList<>();
    buckets = new NodeBucket[KademliaOptions.BINS];
    for (int i = 0; i < KademliaOptions.BINS; i++) {
      buckets[i] = new NodeBucket(i);
    }
  }

  public synchronized Node addNode(Node n) {
    NodeEntry entry = getNodeEntry(n);
    if (entry != null) {
      entry.touch();
      return null;
    }

    NodeEntry e = new NodeEntry(node.getId(), n);
    NodeEntry lastSeen = buckets[getBucketId(e)].addNode(e);
    if (lastSeen != null) {
      return lastSeen.getNode();
    }
    nodes.add(e);
    return null;
  }

  public synchronized void dropNode(Node n) {
    NodeEntry entry = getNodeEntry(n);
    if (entry != null) {
      nodes.remove(entry);
      buckets[getBucketId(entry)].dropNode(entry);
    }
  }

  public synchronized boolean contains(Node n) {
    return getNodeEntry(n) != null;
  }

  public synchronized void touchNode(Node n) {
    NodeEntry entry = getNodeEntry(n);
    if (entry != null) {
      entry.touch();
    }
  }

  public int getBucketsCount() {
    int i = 0;
    for (NodeBucket b : buckets) {
      if (b.getNodesCount() > 0) {
        i++;
      }
    }
    return i;
  }

  public int getBucketId(NodeEntry e) {
    int id = e.getDistance() - 1;
    return id < 0 ? 0 : id;
  }

  public synchronized int getNodesCount() {
    return nodes.size();
  }

  public synchronized List<NodeEntry> getAllNodes() {
    List<NodeEntry> list = new ArrayList<>(nodes);
    list.remove(new NodeEntry(node.getId(), node));
    return list;
  }

  public synchronized List<Node> getClosestNodes(byte[] targetId) {
    List<NodeEntry> closestEntries = getAllNodes();
    List<Node> closestNodes = new ArrayList<>();
    for (NodeEntry e : closestEntries) {
      if (!e.getNode().isDiscoveryNode()) {
        Node node = (Node) e.getNode().clone();
        node.setId(Node.getNodeId());
        closestNodes.add(node);
      }
    }
    Collections.sort(closestNodes, new DistanceComparator(targetId));
    if (closestEntries.size() > KademliaOptions.BUCKET_SIZE) {
      closestNodes = closestNodes.subList(0, KademliaOptions.BUCKET_SIZE);
    }
    return closestNodes;
  }

  private NodeEntry getNodeEntry(Node n) {
    NodeEntry entry = null;
    for (NodeEntry e: nodes) {
      if (e.getNode().getHost().equals(n.getHost())) {
        entry = e;
        break;
      }
    }
    return entry;
  }
}
