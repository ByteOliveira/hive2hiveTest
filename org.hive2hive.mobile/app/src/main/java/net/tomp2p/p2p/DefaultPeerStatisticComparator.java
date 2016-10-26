package net.tomp2p.p2p;

import net.tomp2p.peers.Number160;
import net.tomp2p.peers.PeerMap;
import net.tomp2p.peers.PeerStatistic;

import java.util.Comparator;

/**
 * Default Comparator for PeerStatistics. Compares XOR distance to location.
 *
 * Created by Sebastian Stephan on 28.12.14.
 */
public class DefaultPeerStatisticComparator implements PeerStatisticComparator {
    @Override
    public Comparator<PeerStatistic> getComparator(Number160 location) {
        return PeerMap.createXORStatisticComparator(location);
    }
}
