/*
 * Copyright 2013 Thomas Bocek
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not
 * use this file except in compliance with the License. You may obtain a copy of
 * the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations under
 * the License.
 */
package net.tomp2p.peers;

import net.tomp2p.connection.PeerConnection;
import net.tomp2p.connection.PeerException;
import net.tomp2p.connection.PeerException.AbortCause;
import net.tomp2p.p2p.PeerStatisticComparator;
import net.tomp2p.utils.CacheMap;
import net.tomp2p.utils.ConcurrentCacheMap;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.NavigableSet;
import java.util.SortedSet;
import java.util.TreeSet;

/**
 * This routing implementation uses is based on Kademlia. However, many changes have been applied to make it faster and
 * more flexible. This class is partially thread-safe.
 * 
 * @author Thomas Bocek
 */
public class PeerMap implements PeerStatusListener, Maintainable {
    private static final Logger LOG = LoggerFactory.getLogger(PeerMap.class);

    // each distance bit has its own bag this is the size of the verified peers (the ones that we know are reachable)
    private final int[] bagSizesVerified;
    private final int[] bagSizesOverflow;

    // the id of this node
    private final Number160 self;

    // the storage for the peers that are verified
    private final List<Map<Number160, PeerStatistic>> peerMapVerified;

    // the storage for the peers that are not verified or overflown
    private final List<Map<Number160, PeerStatistic>> peerMapOverflow;

    private final ConcurrentCacheMap<Number160, PeerAddress> offlineMap;
    private final ConcurrentCacheMap<Number160, PeerAddress> shutdownMap;
    private final ConcurrentCacheMap<Number160, PeerAddress> exceptionMap;

    // stores listeners that will be notified if a peer gets removed or added
    private final List<PeerMapChangeListener> peerMapChangeListeners = new ArrayList<PeerMapChangeListener>();

    private final Collection<PeerMapFilter> peerMapFilters;

    // the number of failures until a peer is considered offline
    private final int offlineCount;

    private final Maintenance maintenance;
    
    private final boolean peerVerification;

    private PeerStatisticComparator peerStatisticComparator;

    /**
     * Creates the bag for the peers. This peer knows a lot about close peers and the further away the peers are, the
     * less known they are. Distance is measured with XOR of the peer ID. The distance of peer with ID 0x12 and peer
     * with Id 0x28 is 0x3a.
     * 
     * @param peerMapConfiguration
     *            The configuration values of this map
     * 
     */
	public PeerMap(final PeerMapConfiguration peerMapConfiguration) {
        this.self = peerMapConfiguration.self();
        if (self == null || self.isZero()) {
            throw new IllegalArgumentException("Zero or null are not a valid IDs");
        }
        this.bagSizesVerified = peerMapConfiguration.getVerifiedBagSizes();
        this.bagSizesOverflow = peerMapConfiguration.getOverflowBagSizes();
        this.offlineCount = peerMapConfiguration.offlineCount();
        this.peerMapFilters = peerMapConfiguration.peerMapFilters();
        this.peerMapVerified = initMap(bagSizesVerified, false);
        this.peerMapOverflow = initMap(bagSizesOverflow, true);
        // bagSizeVerified * Number160.BITS should be enough
        this.offlineMap = new ConcurrentCacheMap<Number160, PeerAddress>(
                peerMapConfiguration.offlineTimeout(), totalNumberOfVerifiedBags());
        this.shutdownMap = new ConcurrentCacheMap<Number160, PeerAddress>(
                peerMapConfiguration.shutdownTimeout(), totalNumberOfVerifiedBags());
        this.exceptionMap = new ConcurrentCacheMap<Number160, PeerAddress>(
                peerMapConfiguration.exceptionTimeout(), totalNumberOfVerifiedBags());
        this.maintenance = peerMapConfiguration.maintenance().init(peerMapVerified, peerMapOverflow,
                offlineMap, shutdownMap, exceptionMap);
        this.peerVerification = peerMapConfiguration.isPeerVerification();
        this.peerStatisticComparator = peerMapConfiguration.getPeerStatisticComparator();

    }

    private int totalNumberOfVerifiedBags() {
        int sum = 0;
        for (int i=0; i<Number160.BITS; i++) {
            sum += bagSizesVerified[i];
        }
        return sum;
    }

    /**
     * Create a list of bag with an unmodifiable map.
     *
     * @param bagSizes
     *            The bag sizes
     * @param caching
     *            If a caching map should be created
     *
     * @return The list of bags containing an unmodifiable map
     */
    private List<Map<Number160, PeerStatistic>> initMap(final int[] bagSizes, final boolean caching) {
        List<Map<Number160, PeerStatistic>> tmp = new ArrayList<Map<Number160, PeerStatistic>>();
        for (int i = 0; i < Number160.BITS; i++) {
            // I made some experiments here and concurrent sets are not
            // necessary, as we divide similar to segments aNonBlockingHashSets
            // in a concurrent map. In a full network, we have 160 segments, for
            // smaller we see around 3-4 segments, growing with the number of
            // peers. bags closer to 0 will see more read than write, and bags
            // closer to 160 will see more writes than reads.
            //
            // We also only allocate memory for the bags far away, as they are likely to be filled first.
            if (caching) {
                tmp.add(new CacheMap<Number160, PeerStatistic>(bagSizes[i], true));
            } else {
                final int memAlloc = bagSizes[i] / 8;
                tmp.add(new HashMap<Number160, PeerStatistic>(memAlloc));
            }
        }
        return Collections.unmodifiableList(tmp);
    }

    /**
     * Add a map change listener. This is thread-safe
     * 
     * @param peerMapChangeListener
     *            The listener
     */
    public void addPeerMapChangeListener(final PeerMapChangeListener peerMapChangeListener) {
        synchronized (peerMapChangeListeners) {
            peerMapChangeListeners.add(peerMapChangeListener);
        }

    }

    /**
     * Remove a map change listener. This is thread-safe
     * 
     * @param peerMapChangeListener
     *            The listener
     */
    public void removePeerMapChangeListener(final PeerMapChangeListener peerMapChangeListener) {
        synchronized (peerMapChangeListeners) {
            peerMapChangeListeners.remove(peerMapChangeListener);
        }
    }

    /**
     * Notifies on insert. This is called after the peer has been added to the map.
     * 
     * @param peerAddress
     *            The address of the inserted peers
     * @param verified
     *            True if the peer was inserted in the verified map
     */
    private void notifyInsert(final PeerAddress peerAddress, final boolean verified) {
        synchronized (peerMapChangeListeners) {
            for (PeerMapChangeListener listener : peerMapChangeListeners) {
                listener.peerInserted(peerAddress, verified);
            }
        }
    }

    /**
     * Notifies on remove. Since listeners are never changed, this is thread safe.
     * 
     * @param peerAddress
     *            The address of the removed peers
     * @param storedPeerAddress
     *            Contains information statistical information
     */
    private void notifyRemove(final PeerAddress peerAddress, final PeerStatistic storedPeerAddress) {
        synchronized (peerMapChangeListeners) {
            for (PeerMapChangeListener listener : peerMapChangeListeners) {
                listener.peerRemoved(peerAddress, storedPeerAddress);
            }
        }
    }

    /**
     * Notifies on update. This method is thread safe.
     * 
     * @param peerAddress
     *            The address of the updated peers.
     * @param storedPeerAddress
     *            Contains information statistical information
     */
    private void notifyUpdate(final PeerAddress peerAddress, final PeerStatistic storedPeerAddress) {
        synchronized (peerMapChangeListeners) {
            for (PeerMapChangeListener listener : peerMapChangeListeners) {
                listener.peerUpdated(peerAddress, storedPeerAddress);
            }
        }
    }

    /**
     * The number of the peers in the verified map.
     * 
     * @return the total number of peers
     */
    public int size() {
        int size = 0;
        for (Map<Number160, PeerStatistic> map : peerMapVerified) {
            synchronized (map) {
                size += map.size();
            }
        }
        return size;
    }

    /**
     * Each node that has a bag has an ID itself to define what is close. This method returns this ID.
     * 
     * @return The id of this node
     */
    public Number160 self() {
        return self;
    }
    
    private boolean reject (PeerAddress peerAddress) {
    	if(peerMapFilters == null || peerMapFilters.size() ==0) {
    		return false;
    	}
    	
    	for(PeerMapFilter peerFilter:peerMapFilters) {
    		if(peerFilter.rejectPeerMap(peerAddress, this)) {
    			return true;
    		}
    	}
    	return false;
    }
    

    /**
     * Adds a neighbor to the neighbor list. If the bag is full, the id zero or the same as our id, the neighbor is not
     * added. This method is tread-safe
     * 
     * @param remotePeer
     *            The node that should be added
     * @param referrer
     *            If we had direct contact and we know for sure that this node is online, we set firsthand to true.
     *            Information from 3rd party peers are always second hand and treated as such
     * @param roundTripTime
     *            A RTT object, that measured the round-trip-time related to the finding of this peer. This is
     *            will be saved into the PeerStatistic
     * @return True if the neighbor could be added or updated, otherwise false.
     */
    @Override
    public boolean peerFound(final PeerAddress remotePeer, final PeerAddress referrer, final PeerConnection peerConnection, RTT roundTripTime) {
    	LOG.debug("peer {} is online reporter was {}", remotePeer, referrer);
        boolean firstHand = referrer == null;
        //if we got contacted by this peer, but we did not initiate the connection
        boolean secondHand = remotePeer.equals(referrer);
        //if a peer reported about other peers
        boolean thirdHand = !firstHand && !secondHand;
        // always trust first hand information
        if (firstHand) {
            offlineMap.remove(remotePeer.peerId());
            shutdownMap.remove(remotePeer.peerId());
        }
        
        if (secondHand && !peerVerification) {
        	offlineMap.remove(remotePeer.peerId());
            shutdownMap.remove(remotePeer.peerId());
        }
        
        // don't add nodes with zero node id, do not add myself and do not add
        // nodes marked as bad
        if (remotePeer.peerId().isZero() || self().equals(remotePeer.peerId()) || reject(remotePeer)) {
            return false;
        }
        
        if (remotePeer.isFirewalledTCP() || remotePeer.isFirewalledUDP()) {
        	return false;
        }
        
        //if a peer is relayed but cannot provide any relays, its useless
        if (remotePeer.isRelayed() && remotePeer.peerSocketAddresses().isEmpty()) {
        	return false;
        }
        
        final boolean probablyDead = offlineMap.containsKey(remotePeer.peerId()) || 
        		shutdownMap.containsKey(remotePeer.peerId()) || 
        		exceptionMap.containsKey(remotePeer.peerId());
        
        if(thirdHand && probablyDead) {
        	LOG.debug("don't add {}", remotePeer.peerId());
        	return false;
        }
        
        final int classMember = classMember(remotePeer.peerId());

        // Update existing PeerStatistic with RTT info and potential new port
        final PeerStatistic oldPeerStatistic = updateExistingVerifiedPeerAddress(
                peerMapVerified.get(classMember), remotePeer, firstHand, roundTripTime);
        if (oldPeerStatistic != null) {
            // we update the peer, so we can exit here and report that we have
            // updated it.
            notifyUpdate(remotePeer, oldPeerStatistic);
            return true;
        } else {
            if (firstHand || (secondHand && !peerVerification)) {
                final Map<Number160, PeerStatistic> map = peerMapVerified.get(classMember);
                boolean insterted = false;
                synchronized (map) {
                    // check again, now we are synchronized
                    if (map.containsKey(remotePeer.peerId())) {
                        return peerFound(remotePeer, referrer, peerConnection, roundTripTime);
                    }
                    if (map.size() < bagSizesVerified[classMember]) {
                        final PeerStatistic peerStatistic = new PeerStatistic(remotePeer);
                        peerStatistic.successfullyChecked();
                        peerStatistic.addRTT(roundTripTime);
                        map.put(remotePeer.peerId(), peerStatistic);
                        insterted = true;
                    }
                }

                if (insterted) {
                    // if we inserted into the verified map, remove it from the non-verified map
                    final Map<Number160, PeerStatistic> mapOverflow = peerMapOverflow.get(classMember);
                    synchronized (mapOverflow) {
                        mapOverflow.remove(remotePeer.peerId());
                    }
                    notifyInsert(remotePeer, true);
                    return true;
                }
            }
        }
        // if we are here, we did not have this peer, but our verified map was full
        // check if we have it stored in the non verified map.
        final Map<Number160, PeerStatistic> mapOverflow = peerMapOverflow.get(classMember);
        synchronized (mapOverflow) {
            PeerStatistic peerStatistic = mapOverflow.get(remotePeer.peerId());
            if (peerStatistic == null) {
                peerStatistic = new PeerStatistic(remotePeer);
            }
            if (firstHand) {
                peerStatistic.successfullyChecked();
                peerStatistic.addRTT(roundTripTime);
            }
            if (thirdHand && roundTripTime != null) {
                peerStatistic.addRTT(roundTripTime.setEstimated());
            }
            mapOverflow.put(remotePeer.peerId(), peerStatistic);
        }

        notifyInsert(remotePeer, false);
        return true;
    }

    /**
     * Remove a peer from the list. In order to not reappear, the node is put for a certain time in a cache list to keep
     * the node removed. This method is thread-safe.
     * 
     * @param remotePeer
     *            The node that should be removed
     * @return True if the neighbor was removed and added to a cache list. False if peer has not been removed or is
     *         already in the peer removed temporarily list.
     */
    @Override
    public boolean peerFailed(final PeerAddress remotePeer, final PeerException peerException) {
        LOG.debug("peer {} is offline with reason {}", remotePeer, peerException);
        
        // TB: ignore ZERO peer Id for the moment, but we should filter for the IP address
        if (remotePeer.peerId().isZero() || self().equals(remotePeer.peerId())) {
            return false;
        }
        final int classMember = classMember(remotePeer.peerId());
        AbortCause reason = peerException.abortCause();
        if (reason != AbortCause.TIMEOUT) {
            if(reason == AbortCause.PROBABLY_OFFLINE) {
                offlineMap.put(remotePeer.peerId(), remotePeer);
            } else if(reason == AbortCause.SHUTDOWN) {
                shutdownMap.put(remotePeer.peerId(), remotePeer);
            } else { // reason is exception
                exceptionMap.put(remotePeer.peerId(), remotePeer);
            }
            Map<Number160, PeerStatistic> tmp = peerMapOverflow.get(classMember);
            if (tmp != null) {
                synchronized (tmp) {
                    tmp.remove(remotePeer.peerId());
                }
            }
            tmp = peerMapVerified.get(classMember);
            if (tmp != null) {
                boolean removed = false;
                final PeerStatistic peerStatistic;
                synchronized (tmp) {
                    peerStatistic = tmp.remove(remotePeer.peerId());
                    if (peerStatistic != null) {
                        removed = true;
                    }
                }
                if (removed) {
                    notifyRemove(remotePeer, peerStatistic);
                    return true;
                }
            }
            return false;
        }
        // not forced
        if (updatePeerStatistic(remotePeer, peerMapVerified.get(classMember), offlineCount)) {
            return peerFailed(remotePeer, new PeerException(AbortCause.PROBABLY_OFFLINE, "peer failed in verified map"));
        }
        if (updatePeerStatistic(remotePeer, peerMapOverflow.get(classMember), offlineCount)) {
            return peerFailed(remotePeer, new PeerException(AbortCause.PROBABLY_OFFLINE, "peer failed in overflow map"));
        }
        return false;
    }

    /**
     * Checks if a peer address in either in the verified map.
     * 
     * @param peerAddress
     *            The peer address to check
     * @return True, if the peer address is either in the verified map
     */
    public boolean contains(final PeerAddress peerAddress) {
        final int classMember = classMember(peerAddress.peerId());
        if (classMember == -1) {
            // -1 means we searched for ourself and we never are our neighbor
            return false;
        }
        final Map<Number160, PeerStatistic> tmp = peerMapVerified.get(classMember);
        synchronized (tmp) {
            return tmp.containsKey(peerAddress.peerId());
        }
    }

    /**
     * Checks if a peer address in either in the overflow / non-verified map.
     * 
     * @param peerAddress
     *            The peer address to check
     * @return True, if the peer address is either in the overflow / non-verified map
     */
    public boolean containsOverflow(final PeerAddress peerAddress) {
        final int classMember = classMember(peerAddress.peerId());
        if (classMember == -1) {
            // -1 means we searched for ourself and we never are our neighbor
            return false;
        }
        final Map<Number160, PeerStatistic> tmp = peerMapOverflow.get(classMember);
        synchronized (tmp) {
            return tmp.containsKey(peerAddress.peerId());
        }
    }

    /**
     * Checks if an entry of that peerAddress is available in the verified peer map
     * or overflow peer map.
     * @param peerAddress
     * @return The PeerStatistic or null if peer is not in peer map
     */
    public PeerStatistic getPeerStatistic(final PeerAddress peerAddress) {
        PeerStatistic peerStatistic;
        final int classMember = classMember(peerAddress.peerId());
        if (classMember == -1) {
            // -1 means we searched for ourself and we never are our neighbor
            return null;
        }

        // Try to find PeerStatistic in verified Map
        peerStatistic = peerMapVerified().get(classMember).get(peerAddress.peerId());

        // If that failed, look in the overflow map
        if (peerStatistic == null) {
            peerStatistic = peerMapOverflow().get(classMember).get(peerAddress.peerId());
        }

        return peerStatistic;

    }
    
    /**
     * Returns close peers to the peer itself.
     * @param atLeast The number we want to find at least
     * @return A sorted set with close peers first in this set.
     */
    public NavigableSet<PeerStatistic> closePeers(final int atLeast) {
        return closePeers(self, atLeast);
    }

    /**
     * Returns close peer from the set to a given key. This method is tread-safe. You can use the returned set as its a
     * copy of the actual PeerMap and changes in the return set do not affect PeerMap. 
     * 
     * @param id
     *            The key that should be close to the keys in the map
     * @param atLeast
     *            The number we want to find at least
     * @return A sorted set with close peers first in this set. Use set.first() to get the closest peer
     */
    public NavigableSet<PeerStatistic> closePeers(final Number160 id, final int atLeast) {
    	return closePeers(self(), id, atLeast, peerMapVerified, peerStatisticComparator.getComparator(id));
    }

    public static NavigableSet<PeerStatistic> closePeers(final Number160 self, final Number160 other,
                                                         final int atLeast,
                                                         List<Map<Number160, PeerStatistic>> peerMap,
                                                         Comparator<PeerStatistic> comparator) {

        if (comparator == null) comparator = createXORStatisticComparator(other);
        final NavigableSet<PeerStatistic> set = new TreeSet<PeerStatistic>(comparator);
        final int classMember = classMember(self, other);
        // special treatment, as we can start iterating from 0
        if (classMember == -1) {
            for (int j = 0; j < Number160.BITS; j++) {
                final Map<Number160, PeerStatistic> tmp = peerMap.get(j);
                if (fillSet(atLeast, set, tmp)) {
                    return set;
                }
            }
            return set;
        }

        Map<Number160, PeerStatistic> tmp = peerMap.get(classMember);
        if (fillSet(atLeast, set, tmp)) {
            return set;
        }

        // in this case we have to go over all the bags that are smaller
        boolean last = false;
        for (int i = 0; i < classMember; i++) {
            tmp = peerMap.get(i);
            last = fillSet(atLeast, set, tmp);
        }
        if (last) {
            return set;
        }
        // in this case we have to go over all the bags that are larger
        for (int i = classMember + 1; i < Number160.BITS; i++) {
            tmp = peerMap.get(i);
            fillSet(atLeast, set, tmp);
        }
        return set;
    }
    
    @Override
    public String toString() {
        final StringBuilder sb = new StringBuilder("I'm node ");
        sb.append(self()).append("\n");
        for (int i = 0; i < Number160.BITS; i++) {
            final Map<Number160, PeerStatistic> tmp = peerMapVerified.get(i);
            synchronized (tmp) {
                if (tmp.size() > 0) {
                    sb.append("class:").append(i).append("->\n");
                    for (final PeerStatistic node : tmp.values()) {
                        sb.append("node:").append(node.peerAddress()).append(",");

                    }
                }
            }
        }
        return sb.toString();
    }

    /**
     * Creates an XOR comparator based on this peer ID.
     * 
     * @return The XOR comparator
     */
    public Comparator<PeerAddress> createXORAddressComparator() {
        return createXORAddressComparator(self);
    }

    /**
     * Return all addresses from the neighbor list. The collection is a copy and it is partially sorted. This operation is slow.
     * 
     * @return All neighbors
     */
    public List<PeerAddress> all() {
        List<PeerAddress> all = new ArrayList<PeerAddress>();
        for (Map<Number160, PeerStatistic> map : peerMapVerified) {
            synchronized (map) {
                for (PeerStatistic peerStatistic : map.values()) {
                    all.add(peerStatistic.peerAddress());
                }
            }
        }
        return all;
    }
    
    public List<Map<Number160, PeerStatistic>> peerMapVerified() {
    	return peerMapVerified;
    }
    
    public List<Map<Number160, PeerStatistic>> peerMapOverflow() {
    	return peerMapOverflow;
    }

    /**
     * Return all addresses from the overflow / non-verified list. The collection is a copy and it is partially sorted.
     * 
     * @return All neighbors
     */
    public List<PeerAddress> allOverflow() {
        List<PeerAddress> all = new ArrayList<PeerAddress>();
        for (Map<Number160, PeerStatistic> map : peerMapOverflow) {
            synchronized (map) {
                for (PeerStatistic peerStatistic : map.values()) {
                    all.add(peerStatistic.peerAddress());
                }
            }
        }
        return all;
    }

    /**
     * Checks if a peer is in the offline map.
     * 
     * @param peerAddress
     *            The address to look for
     * @return True if the peer is in the offline map, meaning that we consider this peer offline.
     */
    public boolean isPeerRemovedTemporarly(final PeerAddress peerAddress) {
        return offlineMap.containsKey(peerAddress.peerId())
                || shutdownMap.containsKey(peerAddress.peerId()) 
                || exceptionMap.containsKey(peerAddress.peerId());
    }

    /**
     * Finds the next peer that should have a maintenance check. Returns null if no maintenance is needed at the moment.
     * It will return the most important peers first. Importance is as follows: The most important peers are the close
     * ones in the verified peer map. If a certain threshold in a bag is not reached, the unverified becomes important
     * too.
     * 
     * @return The next most important peer to check if its still alive.
     */
    public PeerStatistic nextForMaintenance(Collection<PeerAddress> notInterestedAddresses) {
        return maintenance.nextForMaintenance(notInterestedAddresses);
    }

    /**
     * Returns the number of the class that this id belongs to.
     * 
     * @param remoteID
     *            The id to test
     * @return The number of bits used in the difference.
     */
    private int classMember(final Number160 remoteID) {
        return classMember(self(), remoteID);
    }

    /**
     * Returns -1 if the first remote node is closer to the key, if the secondBITS is closer, then 1 is returned. If
     * both are equal, 0 is returned
     * 
     * @param id
     *            The id as a distance reference
     * @param rn
     *            The peer to test if closer to the id
     * @param rn2
     *            The other peer to test if closer to the id
     * @return -1 if first peer is closer, 1 otherwise, 0 if both are equal
     */
    public static int isKadCloser(final Number160 id, final PeerAddress rn, final PeerAddress rn2) {
        return distance(id, rn.peerId()).compareTo(distance(id, rn2.peerId()));
    }

    /**
     * Returns -1 if the first remote node is closer to the key, if the secondBITS is closer, then 1 is returned. If
     * both are equal, 0 is returned
     *
     * @param ln The location
     * @param rn The peer to test if closer to the id
     * @param rn2 The other peer to test if closer to the id
     * @return -1 if rn2 is closer to ln (in terms of xor distance bit length), 1 if
     *          rn1 is closer or 0 if they are equal.
     */
    public static int classCloser(final Number160 ln, final PeerAddress rn, final PeerAddress rn2) {
        Integer d1 = classMember(ln, rn.peerId());
        Integer d2 = classMember(ln, rn2.peerId());
        return d1.compareTo(d2);
    }

    /**
     * Create the Kademlia distance comparator.
     * 
     * @param location
     *            The id of this peer
     * @return The XOR comparator
     */
    public static Comparator<PeerAddress> createXORAddressComparator(final Number160 location) {
        return new Comparator<PeerAddress>() {
            public int compare(final PeerAddress remotePeer, final PeerAddress remotePeer2) {
                return isKadCloser(location, remotePeer, remotePeer2);
            }
        };
    }

    public static Comparator<PeerStatistic> createXORStatisticComparator(final Number160 location) {
        return new Comparator<PeerStatistic>() {
            @Override
            public int compare(PeerStatistic o1, PeerStatistic o2) {
                if (o1.peerAddress() != null && o2.peerAddress() != null) {
                    return isKadCloser(location, o1.peerAddress(), o2.peerAddress());
                }
                return 0;
            }
        };
    }


    public Comparator<PeerStatistic> createStatisticComparator(final Number160 location) {
        return peerStatisticComparator.getComparator(location);
    }

    /**
     * Takes a collection of PeerAddress and returns an equivalent collection
     * of PeerStatistic from the PeerMap. New PeerStatistics are created for peers
     * that are not yet in the PeerMap
     * @param peerAddresses
     * @return
     */
    public Collection<PeerStatistic> getPeerStatistics(Collection<PeerAddress> peerAddresses) {
        HashSet<PeerStatistic> result = new HashSet<PeerStatistic>();
        for (PeerAddress peerAddress : peerAddresses) {
            PeerStatistic peerStatistic = getPeerStatistic(peerAddress);
            if (peerStatistic == null) {
                peerStatistic = new PeerStatistic(peerAddress);
            }
            result.add(peerStatistic);
        }
        return result;
    }

    /**
     * Returns the difference in terms of bit counts of two ids, minus 1. So two IDs with one bit difference are in the
     * class 0.
     * 
     * @param id1
     *            The first id
     * @param id2
     *            The second id
     * @return returns the bit difference and -1 if they are equal
     */
    public static int classMember(final Number160 id1, final Number160 id2) {
        return distance(id1, id2).bitLength() - 1;
    }

    /**
     * The distance metric is the XOR metric.
     * 
     * @param id1
     *            The first id
     * @param id2
     *            The second id
     * @return The distance
     */
    static Number160 distance(final Number160 id1, final Number160 id2) {
        return id1.xor(id2);
    }

    /**
     * Updates the peer statistics and checks if the max failure has been reached.
     * 
     * @param remotePeer
     *            The remote peer
     * @param tmp
     *            The bag of where the peer is supposed to be
     * @param maxFail
     *            The number of max failure until a peer is considered offline
     * @return True if this peer is considered offline, otherwise false
     */
    private static boolean updatePeerStatistic(final PeerAddress remotePeer,
            final Map<Number160, PeerStatistic> tmp, final int maxFail) {
        if (tmp != null) {
            synchronized (tmp) {
                PeerStatistic peerStatistic = tmp.get(remotePeer.peerId());
                if (peerStatistic != null) {
                    if (peerStatistic.failed() >= maxFail) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    /**
     * Checks if a peer already exists in this map and if it does, it will update the entry because the peer address
     * (e.g. port) may have changed.
     * 
     * @param tmp
     *            The map where the peer is suppost to be
     * @param peerAddress
     *            The address of the peer that may have been changed
     * @param firstHand
     *            True if this peer send and received a message from the remote peer
     * @param roundTripTime
     * @return The old peer address if we have updated the peer, null otherwise
     */
    public static PeerStatistic updateExistingVerifiedPeerAddress(
            final Map<Number160, PeerStatistic> tmp, final PeerAddress peerAddress, final boolean firstHand, RTT roundTripTime) {
        synchronized (tmp) {
            PeerStatistic old = tmp.get(peerAddress.peerId());
            if (old != null) {
            	//TODO: this should only be from firsthand!
                old.peerAddress(peerAddress);
                old.addRTT(roundTripTime);
                if (firstHand) {
                    old.successfullyChecked();
                    old.increaseNumberOfResponses();
                }
                return old;
            }
        }
        return null;
    }

    /**
     * Fills the set with peer addresses. Fills it until a limit is reach. However, this is a soft limit, as the bag may
     * contain close peers in a random manner.
     * 
     * @param atLeast
     *            The number of addresses we want at least. It does not matter if its more.
     * @param set
     *            The set where to store the results
     * @param tmp
     *            The bag where to take the addresses from
     * @return True if the desired size has been reached
     */
    private static boolean fillSet(final int atLeast, final SortedSet<PeerStatistic> set,
            final Map<Number160, PeerStatistic> tmp) {
        synchronized (tmp) {
            for (final PeerStatistic peerStatistic : tmp.values()) {
                set.add(peerStatistic);
            }
        }
        return set.size() >= atLeast;
    }

	public int bagSizeVerified(int bag) {
	    return bagSizesVerified[bag];
    }
	
	public int bagSizeOverflow(int bag) {
	    return bagSizesOverflow[bag];
    }
}
