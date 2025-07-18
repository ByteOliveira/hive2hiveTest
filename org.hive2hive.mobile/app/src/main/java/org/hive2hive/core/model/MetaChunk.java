package org.hive2hive.core.model;

import org.hive2hive.core.security.HashUtil;

import java.io.Serializable;
import java.util.Arrays;

/**
 * Holds meta data of a chunk in the DHT
 * 
 * @author Seppi
 */
public class MetaChunk implements Serializable {

	private static final long serialVersionUID = -2463290285291070943L;

	private final String chunkId;
	private final byte[] chunkHash;
	private final int index;

	public MetaChunk(String chunkId, byte[] chunkHash, int index) {
		this.chunkId = chunkId;
		this.chunkHash = chunkHash;
		this.index = index;
	}

	/**
	 * The id of a chunk. In a 'small' file, this id serves as the location key. In a 'large' file, this id is
	 * used to ask other clients.
	 * 
	 * @return the chunk id
	 */
	public String getChunkId() {
		return chunkId;
	}

	/**
	 * The hash of the chunk. In a 'small' file, this hash is generated by TomP2P and required here to re-key
	 * a chunk at sharing. In a 'large' file, this hash is used to verify the data sent from another peer.
	 * 
	 * @return the hash
	 */
	public byte[] getChunkHash() {
		return chunkHash;
	}

	/**
	 * The index of the chunk, used to contatenate the file later
	 * 
	 * @return the index
	 */
	public int getIndex() {
		return index;
	}

	@Override
	public int hashCode() {
		return Arrays.hashCode(chunkHash);
	}

	@Override
	public boolean equals(Object obj) {
		if (obj == null) {
			return false;
		}

		if (obj instanceof MetaChunk) {
			MetaChunk other = (MetaChunk) obj;
			return other.getChunkId().equalsIgnoreCase(chunkId) && other.getIndex() == index
					&& HashUtil.compare(chunkHash, other.getChunkHash());
		}

		return false;
	}

}
